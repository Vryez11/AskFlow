package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionFailure
import io.askflow.pipeline.datasource.ConnectionFailureReason
import io.askflow.pipeline.datasource.ConnectionState
import io.askflow.pipeline.datasource.ConnectionStatus
import io.askflow.pipeline.datasource.TargetDbConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** data-connection의 연결 확인과 권한 점검 시나리오 (F-003) */
class ConnectionCheckIT {
    private fun check(config: TargetDbConfig = TestMySql.config()): ConnectionStatus = MySqlConnector(config).checkConnection()

    private fun connected(config: TargetDbConfig = TestMySql.config()): ConnectionStatus {
        val status = check(config)
        assertEquals(ConnectionState.CONNECTED, status.state, "상태: $status")
        assertNull(status.failure, "상태: $status")
        return status
    }

    private fun failed(
        reason: ConnectionFailureReason,
        config: TargetDbConfig,
    ): ConnectionFailure {
        val status = check(config)
        assertEquals(ConnectionState.FAILED, status.state, "상태: $status")
        val failure = assertNotNull(status.failure, "상태: $status")
        assertEquals(reason, failure.reason, "실패: $failure")
        assertFalse(status.toString().contains(config.password), "상태에 비밀번호가 있다: $status")
        return failure
    }

    // 설정 파일로 연결

    @Test
    fun `F-003 설정한 DB에 연결 - 연결됨이고 테이블 수는 허용 테이블 수다`() {
        val status = connected()

        assertEquals(TestMySql.tables.size, status.tableCount)
    }

    @Test
    fun `F-003 허용 테이블을 설정하지 않음 - 스키마의 모든 테이블과 뷰가 테이블 수에 들어간다`() {
        val status = connected(TestMySql.config(allowedTables = emptyList()))

        assertEquals(TestMySql.tables.size + TestMySql.views.size, status.tableCount)
    }

    // 연결 실패에도 앱은 뜬다

    @Test
    fun `F-003 비밀번호가 틀림 - 연결 실패, 인증 실패, 계정 이름과 비밀번호 환경 변수를 확인하게 한다`() {
        val failure = failed(ConnectionFailureReason.AUTHENTICATION, TestMySql.config(password = "wrong-pw-1234"))

        assertEquals(listOf("askflow.target-db.username", "ASKFLOW_READER_PASSWORD"), failure.checkKeys)
    }

    @Test
    fun `F-003 DB에 닿지 않음 - 연결 실패, 접속 불가, 접속 주소를 확인하게 하고 오래 기다리지 않는다`() {
        // 아무것도 듣지 않는 포트. 꺼진 DB와 같다
        val config = TestMySql.config().copy(url = "jdbc:mysql://127.0.0.1:1/${TestMySql.SCHEMA}")
        val started = System.nanoTime()

        val failure = failed(ConnectionFailureReason.UNREACHABLE, config)

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofSeconds(10), "connectionTimeout 3초인데 ${elapsed.toMillis()}ms 걸렸다")
        assertEquals(listOf("askflow.target-db.url"), failure.checkKeys)
    }

    @Test
    fun `F-003 스키마가 없음 - DB에 없는 스키마면 연결 실패이고 스키마 이름을 확인하게 한다`() {
        val schema = "no_such_schema"
        val config = TestMySql.config().copy(url = TestMySql.jdbcUrlOf(schema), schema = schema)

        val failure = failed(ConnectionFailureReason.SCHEMA_UNAVAILABLE, config)

        assertEquals(listOf("askflow.target-db.schema"), failure.checkKeys)
    }

    @Test
    fun `F-003 스키마가 없음 - 계정이 접근할 수 없는 스키마면 연결 실패이고 스키마 이름을 확인하게 한다`() {
        val schema = TestMySql.FORBIDDEN_SCHEMA
        val config =
            TestMySql.config(allowedTables = emptyList()).copy(url = TestMySql.jdbcUrlOf(schema), schema = schema)

        val failure = failed(ConnectionFailureReason.SCHEMA_UNAVAILABLE, config)

        assertEquals(listOf("askflow.target-db.schema"), failure.checkKeys)
    }

    @Test
    fun `F-003 허용 테이블이 스키마에 없음 - 연결 실패, 원인에 없는 테이블 이름, 허용 테이블 목록을 확인하게 한다`() {
        val config = TestMySql.config(allowedTables = TestMySql.tables + "no_such_table")

        val failure = failed(ConnectionFailureReason.TABLE_NOT_FOUND, config)

        assertContains(failure.message, "no_such_table")
        assertFalse(TestMySql.tables.any { failure.message.contains(it) }, "있는 테이블까지 원인에 있다: ${failure.message}")
        assertEquals(listOf("askflow.target-db.allowed-tables"), failure.checkKeys)
    }

    // 쓰기 권한 경고

    @Test
    fun `F-003 읽기 전용 계정 - SELECT와 SHOW VIEW만 있으면 쓰기 권한 경고가 없다`() {
        val status = connected()

        assertEquals(emptyList(), status.writePrivileges)
        assertEquals(emptyList(), status.warnings)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 연결됨이고 쓰기 권한 경고에 INSERT가 들어 있다`() {
        val status = connected(TestMySql.config(username = TestMySql.WRITER, password = TestMySql.WRITER_PASSWORD))

        for (privilege in listOf("INSERT", "UPDATE", "DELETE")) {
            assertContains(status.writePrivileges, privilege)
        }
        assertFalse("SELECT" in status.writePrivileges, "쓰기 권한: ${status.writePrivileges}")
    }

    @Test
    fun `F-003 전체 권한 계정 - 모든 DB에 ALL PRIVILEGES가 있으면 연결됨이고 쓰기 권한 경고가 있다`() {
        val status = connected(TestMySql.config(username = TestMySql.ADMIN, password = TestMySql.ADMIN_PASSWORD))

        assertTrue(status.writePrivileges.isNotEmpty(), "전체 권한 계정인데 쓰기 권한 경고가 없다: $status")
        assertContains(status.writePrivileges, "INSERT")
    }

    // 연결 상태 조회

    @Test
    fun `F-003 나중에 살아난 DB - DB가 꺼진 채 만든 커넥터도 DB를 켜고 다시 확인하면 연결됨이 된다`() {
        TcpForwarder(TestMySql.host, TestMySql.port).use { forwarder ->
            val config = TestMySql.config().copy(url = "jdbc:mysql://127.0.0.1:${forwarder.port}/${TestMySql.SCHEMA}")
            val connector = MySqlConnector(config)

            val before = connector.checkConnection()
            assertEquals(ConnectionState.FAILED, before.state, "상태: $before")
            assertEquals(ConnectionFailureReason.UNREACHABLE, before.failure?.reason, "상태: $before")

            forwarder.start()

            val after = connector.checkConnection()
            assertEquals(ConnectionState.CONNECTED, after.state, "상태: $after")
            assertEquals(TestMySql.tables.size, after.tableCount)
            assertTrue(after.checkedAt > before.checkedAt, "확인 시각이 바뀌어야 한다: ${before.checkedAt} → ${after.checkedAt}")
        }
    }
}
