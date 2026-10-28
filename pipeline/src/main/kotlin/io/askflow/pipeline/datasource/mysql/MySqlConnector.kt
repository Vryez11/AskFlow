package io.askflow.pipeline.datasource.mysql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import io.askflow.pipeline.datasource.guard.SqlGuard
import java.sql.Connection
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
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

    // 빈 허용 목록이면 연결 확인에서 읽은 스키마 테이블을 쓴다 (3.5)
    private val guard = SqlGuard(config.schema, config.allowedTables.toSet(), config.maxRows)
    private val lineage = PiiLineage(config.piiColumns)
    private val marker = PiiColumnMarker(config.piiColumns)

    override fun execute(sql: String): ExecutionOutcome =
        when (val checked = guard.check(sql)) {
            is GuardResult.Rejected -> ExecutionOutcome.Failure(checked.error)
            is GuardResult.Passed -> run(checked.sql)
        }

    override fun checkConnection(): ConnectionStatus = TODO()

    private fun run(sql: String): ExecutionOutcome =
        try {
            dataSource.connection.use { connection ->
                try {
                    query(connection, sql)
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
        )

    private fun withoutSecret(text: String): String = if (config.password.isEmpty()) text else text.replace(config.password, "****")

    private fun query(
        connection: Connection,
        sql: String,
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
                val pii = markPii(sql, meta)
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
    ): List<Boolean> {
        val origins =
            (1..meta.columnCount).map { i ->
                // Connector/J의 getTableName·getColumnName은 별칭이 아닌 원래 테이블·컬럼이다
                val table = meta.getTableName(i).orEmpty()
                val column = meta.getColumnName(i).orEmpty()
                if (table.isEmpty() || column.isEmpty()) null else ColumnOrigin(table, column)
            }
        val items = runCatching { lineage.trace(sql) }.getOrNull() ?: return origins.map { true }
        return marker.mark(items, origins)
    }

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
