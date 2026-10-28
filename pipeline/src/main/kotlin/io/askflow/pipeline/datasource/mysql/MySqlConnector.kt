package io.askflow.pipeline.datasource.mysql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.askflow.pipeline.datasource.ConnectionFailure
import io.askflow.pipeline.datasource.ConnectionFailureReason
import io.askflow.pipeline.datasource.ConnectionState
import io.askflow.pipeline.datasource.ConnectionStatus
import io.askflow.pipeline.datasource.DataSourceConnector
import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.QueryResult
import io.askflow.pipeline.datasource.ResultColumn
import io.askflow.pipeline.datasource.TargetDbConfig
import io.askflow.pipeline.datasource.guard.GuardResult
import io.askflow.pipeline.datasource.guard.PiiLineage
import io.askflow.pipeline.datasource.guard.PiiSchemaObjects
import io.askflow.pipeline.datasource.guard.SqlGuard
import java.sql.Connection
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * MySQL 커넥터 (design 1·4장). 풀은 HikariCP로 만든다.
 * 테스트는 internal 생성자로 가짜 [DataSource]를 넣는다.
 */
class MySqlConnector internal constructor(
    private val config: TargetDbConfig,
    private val dataSource: DataSource,
) : DataSourceConnector {
    constructor(config: TargetDbConfig) : this(config, createPool(config))

    private val configuredGuard =
        if (config.allowedTables.isEmpty()) null else SqlGuard(config.schema, config.allowedTables.toSet(), config.maxRows)

    // 빈 허용 목록이면 연결 확인에서 읽은 스키마의 테이블·뷰로 검사한다 (design 6장)
    @Volatile
    private var schemaGuard: SqlGuard? = null
    private val failureClassifier = ConnectionFailureClassifier(config.password)
    private val grantParser = GrantParser(config.schema)

    override fun execute(sql: String): ExecutionOutcome {
        // 허용 목록을 아직 못 읽었으면 검사할 수 없으니 DB에 보내지 않는다
        val guard = configuredGuard ?: schemaGuard ?: return ExecutionOutcome.Failure(connectionFailed())
        return when (val checked = guard.check(sql)) {
            is GuardResult.Rejected -> ExecutionOutcome.Failure(checked.error)
            is GuardResult.Passed -> run(checked.sql)
        }
    }

    override fun checkConnection(): ConnectionStatus {
        val checkedAt = Instant.now()
        return try {
            dataSource.connection.use { connection ->
                try {
                    inspect(connection, checkedAt)
                } finally {
                    connection.rollback()
                }
            }
        } catch (e: SQLException) {
            failed(failureClassifier.classify(e), checkedAt)
        }
    }

    /** 권한 → 스키마 → 허용 테이블 순서로 확인한다 (design 5·6장) */
    private fun inspect(
        connection: Connection,
        checkedAt: Instant,
    ): ConnectionStatus {
        val grants = grantParser.parse(strings(connection, "SHOW GRANTS FOR CURRENT_USER()"))
        if (strings(connection, SCHEMA_QUERY, config.schema).isEmpty()) {
            return failed(schemaUnavailable(), checkedAt)
        }
        val schemaTables = strings(connection, TABLES_QUERY, config.schema)
        val tableCount =
            if (config.allowedTables.isEmpty()) {
                schemaGuard = SqlGuard(config.schema, schemaTables.toSet(), config.maxRows)
                schemaTables.size
            } else {
                val existing = schemaTables.map { it.lowercase() }.toSet()
                val missing = config.allowedTables.filter { it.lowercase() !in existing }
                if (missing.isNotEmpty()) return failed(tableNotFound(missing), checkedAt)
                config.allowedTables.distinctBy { it.lowercase() }.size
            }
        return ConnectionStatus(
            state = ConnectionState.CONNECTED,
            tableCount = tableCount,
            writePrivileges = grants.writePrivileges,
            warnings = grants.warnings,
            failure = null,
            checkedAt = checkedAt,
        )
    }

    /** 고정 조회의 첫 열을 모두 읽는다. 연결 확인 전용이라 바깥에서 SQL을 받지 않는다 */
    private fun strings(
        connection: Connection,
        sql: String,
        vararg params: String,
    ): List<String> =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { i, param -> statement.setString(i + 1, param) }
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun failed(
        failure: ConnectionFailure,
        checkedAt: Instant,
    ): ConnectionStatus =
        ConnectionStatus(
            state = ConnectionState.FAILED,
            tableCount = 0,
            writePrivileges = emptyList(),
            warnings = emptyList(),
            failure = failure,
            checkedAt = checkedAt,
        )

    // 접속 주소의 스키마와 설정 스키마가 달라도 여기서 걸린다. 권한 없는 스키마는 SCHEMATA에 보이지 않는다
    private fun schemaUnavailable(): ConnectionFailure =
        ConnectionFailure(
            reason = ConnectionFailureReason.SCHEMA_UNAVAILABLE,
            message = "스키마가 없거나 이 계정에 스키마 접근 권한이 없습니다.",
            checkKeys = listOf("askflow.target-db.schema"),
        )

    private fun tableNotFound(missing: List<String>): ConnectionFailure =
        ConnectionFailure(
            reason = ConnectionFailureReason.TABLE_NOT_FOUND,
            message = "허용 테이블 중 스키마에 없는 것이 있습니다: ${missing.joinToString(", ")}",
            checkKeys = listOf("askflow.target-db.allowed-tables"),
        )

    /**
     * 정의를 따라가지 않고 개인정보로 볼 뷰, 생성 컬럼, 저장 함수를 한 번의 조회로 읽는다 (design 7장).
     * 접속 주소의 기본 스키마가 설정 스키마와 달라도 테이블 이름만 쓴 SQL은 기본 스키마로 가니 둘 다 읽는다.
     */
    private fun readSchemaObjects(connection: Connection): PiiSchemaObjects {
        val views = mutableListOf<String>()
        val generated = mutableListOf<Pair<String, String>>()
        val functions = mutableListOf<String>()
        connection.prepareStatement(SCHEMA_OBJECTS_QUERY).use { statement ->
            repeat(3) { statement.setString(it + 1, config.schema) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(2).orEmpty()
                    when (rs.getString(1)) {
                        "VIEW" -> views += name
                        "GENERATED" -> generated += name to rs.getString(3).orEmpty()
                        "FUNCTION" -> functions += name
                    }
                }
            }
        }
        return PiiSchemaObjects(views, generated, functions)
    }

    private fun run(sql: String): ExecutionOutcome =
        try {
            dataSource.connection.use { connection ->
                try {
                    // 실행마다 같은 연결에서 다시 읽는다. 읽지 못하면 가릴 수 없으니 실행하지 않는다
                    val objects =
                        try {
                            readSchemaObjects(connection)
                        } catch (e: SQLException) {
                            return ExecutionOutcome.Failure(connectionFailed())
                        }
                    val judge = PiiJudge(PiiLineage(config.piiColumns, objects), PiiColumnMarker(config.piiColumns, objects))
                    query(connection, sql, judge)
                } finally {
                    connection.rollback()
                }
            }
        } catch (e: SQLException) {
            ExecutionOutcome.Failure(classify(e))
        }

    /** 3024·[SQLTimeoutException] → 시간 상한, 통신 오류(`08*`) → 연결 실패, 그 밖 → DB 오류 (design 4장) */
    private fun classify(error: SQLException): ExecutionError {
        val chain = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().toList()
        return when {
            chain.any { it is SQLTimeoutException || it.errorCode == MAX_EXECUTION_TIME_EXCEEDED } -> timeout()
            chain.any { it is SQLTransientConnectionException || it.sqlState?.startsWith("08") == true } -> connectionFailed()
            else -> dbError(chain.last())
        }
    }

    private fun timeout(): ExecutionError =
        ExecutionError(
            kind = ExecutionErrorKind.TIMEOUT,
            message = "실행 시간 상한을 넘어 중단했습니다. 조건을 좁히거나 집계 범위를 줄여 주세요.",
            cause = "상한 ${formatSeconds(config.queryTimeout)}",
            checkPoints = listOf("askflow.target-db.query-timeout"),
        )

    private fun connectionFailed(): ExecutionError =
        ExecutionError(
            kind = ExecutionErrorKind.CONNECTION_FAILED,
            message = "분석 대상 DB에 연결하지 못했습니다.",
            cause = "DB 연결 실패",
            checkPoints = listOf("홈의 연결 상태", "askflow.target-db.url"),
        )

    private fun dbError(error: SQLException): ExecutionError =
        ExecutionError(
            kind = ExecutionErrorKind.DB_ERROR,
            message = "DB가 SQL을 실행하지 못했습니다.",
            cause = withoutSecret(error.message ?: "오류 코드 ${error.errorCode}"),
            checkPoints = listOf("실행하려던 SQL"),
            dbErrorCode = error.errorCode,
            sqlState = error.sqlState,
        )

    private fun withoutSecret(text: String): String = if (config.password.isEmpty()) text else text.replace(config.password, "****")

    private fun query(
        connection: Connection,
        sql: String,
        judge: PiiJudge,
    ): ExecutionOutcome {
        // 세션은 이미 읽기 전용이다. 드라이버 쪽 검사도 더한다(서버로는 보내지 않음, readOnlyPropagatesToServer)
        connection.isReadOnly = true
        connection.autoCommit = false
        val started = System.nanoTime()
        connection.createStatement().use { statement ->
            // DB 쪽 max_execution_time이 먼저 걸리고, 앱 쪽은 그보다 1초 늦게 걸린다
            statement.queryTimeout = appTimeoutSeconds(config.queryTimeout)
            // LIMIT 다시 쓰기가 빠져도 가져오는 행 수를 묶는다
            statement.maxRows = config.maxRows + 1
            statement.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                val pii = markPii(sql, meta, judge)
                val columns = (1..meta.columnCount).map { ResultColumn(name = meta.getColumnLabel(it), pii = pii[it - 1]) }
                val rows =
                    buildList {
                        while (rs.next()) add(columns.indices.map { rs.getObject(it + 1) })
                    }
                val elapsed = Duration.ofNanos(System.nanoTime() - started)
                if (rows.size > config.maxRows) return rowLimitExceeded()
                return ExecutionOutcome.Success(QueryResult(columns, rows, elapsed, sql))
            }
        }
    }

    /** 해석 트리와 메타데이터 중 하나라도 개인정보라고 하면 가린다. 해석 트리를 못 읽으면 모두 가린다 (design 7장) */
    private fun markPii(
        sql: String,
        meta: ResultSetMetaData,
        judge: PiiJudge,
    ): List<Boolean> {
        val origins =
            (1..meta.columnCount).map { i ->
                // Connector/J의 getTableName·getColumnName은 별칭이 아닌 원래 테이블·컬럼이다
                val table = meta.getTableName(i).orEmpty()
                val column = meta.getColumnName(i).orEmpty()
                if (table.isEmpty() || column.isEmpty()) null else ColumnOrigin(table, column)
            }
        val items = runCatching { judge.lineage.trace(sql) }.getOrNull() ?: return origins.map { true }
        return judge.marker.mark(items, origins)
    }

    /** 이번 실행에서 읽은 스키마 객체로 만든 해석 트리 판정과 메타데이터 판정 */
    private class PiiJudge(
        val lineage: PiiLineage,
        val marker: PiiColumnMarker,
    )

    private fun rowLimitExceeded(): ExecutionOutcome.Failure =
        ExecutionOutcome.Failure(
            ExecutionError(
                kind = ExecutionErrorKind.ROW_LIMIT_EXCEEDED,
                message = "결과가 행 수 상한보다 많습니다. 조건을 좁히거나 집계해 주세요.",
                cause = "상한 ${config.maxRows}행",
                checkPoints = listOf("askflow.target-db.max-rows"),
            ),
        )

    internal companion object {
        private const val POOL_SIZE = 4
        private val CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** MySQL ER_QUERY_TIMEOUT: max_execution_time 초과 */
        private const val MAX_EXECUTION_TIME_EXCEEDED = 3024

        private const val SCHEMA_QUERY = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?"

        // 테이블과 뷰 모두
        private const val TABLES_QUERY = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?"

        // 뷰는 정의를 볼 권한(SHOW VIEW)이 없어도 보이도록 VIEWS가 아니라 TABLES에서 읽는다
        private const val SCHEMA_OBJECTS_QUERY =
            "SELECT 'VIEW', TABLE_NAME, NULL FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA IN (?, DATABASE()) AND TABLE_TYPE = 'VIEW' " +
                "UNION ALL SELECT 'GENERATED', TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA IN (?, DATABASE()) AND GENERATION_EXPRESSION <> '' " +
                "UNION ALL SELECT 'FUNCTION', ROUTINE_NAME, NULL FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA IN (?, DATABASE()) AND ROUTINE_TYPE = 'FUNCTION'"

        // 통합 테스트가 커넥터와 같은 세션 설정으로 검사 없이 문장을 보내 볼 때도 쓴다
        fun createPool(config: TargetDbConfig): DataSource =
            HikariDataSource(
                HikariConfig().apply {
                    poolName = "askflow-target-db"
                    jdbcUrl = config.url
                    username = config.username
                    password = config.password
                    // 읽기 전용은 DB 세션에 건다(connectionInitSql). JDBC readOnly를 켜면 드라이버가 먼저 거부해
                    // 세션 겹을 따로 확인할 수 없고, 풀이 반납 때 readOnly를 되돌리며 READ WRITE를 보낸다
                    isReadOnly = false
                    addDataSourceProperty("readOnlyPropagatesToServer", "false")
                    isAutoCommit = false
                    maximumPoolSize = POOL_SIZE
                    connectionTimeout = CONNECTION_TIMEOUT.toMillis()
                    // DB가 없어도 풀 생성은 실패하지 않는다. 앱은 뜨고 연결 상태만 실패가 된다
                    initializationFailTimeout = -1
                    // SET SESSION TRANSACTION READ ONLY는 다른 변수와 한 문에 묶을 수 없어 변수로 건다
                    connectionInitSql =
                        "SET SESSION transaction_read_only = ON, SESSION max_execution_time = ${config.queryTimeout.toMillis()}"
                },
            )

        /** 오류 원인에 쓰는 상한 표기. 1초, 1.5초 */
        private fun formatSeconds(timeout: Duration): String {
            val millis = timeout.toMillis()
            return if (millis % 1000 == 0L) "${millis / 1000}초" else "${millis / 1000.0}초"
        }

        /** 앱 쪽 상한은 DB 쪽 상한 + 1초, 초 단위로 올림 */
        private fun appTimeoutSeconds(timeout: Duration): Int {
            val millis = timeout.toMillis() + 1000
            return ((millis + 999) / 1000).toInt()
        }
    }
}
