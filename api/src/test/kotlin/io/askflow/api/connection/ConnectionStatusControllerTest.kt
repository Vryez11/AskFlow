package io.askflow.api.connection

import io.askflow.pipeline.datasource.ConnectionFailure
import io.askflow.pipeline.datasource.ConnectionFailureReason
import io.askflow.pipeline.datasource.ConnectionState
import io.askflow.pipeline.datasource.ConnectionStatus
import io.askflow.pipeline.datasource.DataSourceConnector
import io.askflow.pipeline.datasource.ExecutionOutcome
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** data-connection "연결 상태 조회" 시나리오 (F-003). 커넥터는 가짜를 쓴다 */
@WebMvcTest(ConnectionStatusController::class)
@Import(ZeroTermCountProvider::class)
class ConnectionStatusControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var connector: FakeConnector

    private val checkedAt = Instant.parse("2026-10-28T01:02:03Z")

    @BeforeTest
    fun reset() {
        connector.calls = 0
    }

    private fun connected(
        tableCount: Int = 9,
        writePrivileges: List<String> = emptyList(),
    ) = ConnectionStatus(
        state = ConnectionState.CONNECTED,
        tableCount = tableCount,
        writePrivileges = writePrivileges,
        warnings = emptyList(),
        failure = null,
        checkedAt = checkedAt,
    )

    private fun failed(failure: ConnectionFailure) =
        ConnectionStatus(
            state = ConnectionState.FAILED,
            tableCount = 0,
            writePrivileges = emptyList(),
            warnings = emptyList(),
            failure = failure,
            checkedAt = checkedAt,
        )

    private fun statusBody(): String =
        mockMvc
            .get("/api/connection/status")
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsString

    @Test
    fun `F-003 정상 연결 상태 - 연결됨, 테이블 수 9, 용어 수, 쓰기 권한 경고 없음을 담는다`() {
        connector.status = connected(tableCount = 9)

        mockMvc.get("/api/connection/status").andExpect {
            status { isOk() }
            jsonPath("$.state") { value("CONNECTED") }
            jsonPath("$.tableCount") { value(9) }
            jsonPath("$.termCount") { isNumber() }
            jsonPath("$.writePrivileges") { isEmpty() }
            jsonPath("$.warnings") { isEmpty() }
            jsonPath("$.failure") { doesNotExist() }
            jsonPath("$.checkedAt") { value("2026-10-28T01:02:03Z") }
        }
    }

    @Test
    fun `F-003 용어 사전이 아직 없음 - 용어 수는 0이다`() {
        connector.status = connected()

        mockMvc.get("/api/connection/status").andExpect {
            jsonPath("$.termCount") { value(0) }
        }
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 연결됨이고 쓰기 권한 경고에 권한 이름이 들어 있다`() {
        connector.status = connected(writePrivileges = listOf("INSERT", "UPDATE"))

        mockMvc.get("/api/connection/status").andExpect {
            jsonPath("$.state") { value("CONNECTED") }
            jsonPath("$.writePrivileges[0]") { value("INSERT") }
            jsonPath("$.writePrivileges[1]") { value("UPDATE") }
        }
    }

    @Test
    fun `F-003 비밀번호가 틀림 - 연결 실패와 원인, 확인할 설정 키를 담는다`() {
        connector.status =
            failed(
                ConnectionFailure(
                    reason = ConnectionFailureReason.AUTHENTICATION,
                    message = "계정 이름이나 비밀번호가 맞지 않습니다",
                    checkKeys = listOf("askflow.target-db.username", "ASKFLOW_READER_PASSWORD"),
                ),
            )

        mockMvc.get("/api/connection/status").andExpect {
            status { isOk() }
            jsonPath("$.state") { value("FAILED") }
            jsonPath("$.failure.reason") { value("AUTHENTICATION") }
            jsonPath("$.failure.message") { value("계정 이름이나 비밀번호가 맞지 않습니다") }
            jsonPath("$.failure.checkKeys[0]") { value("askflow.target-db.username") }
            jsonPath("$.failure.checkKeys[1]") { value("ASKFLOW_READER_PASSWORD") }
            jsonPath("$.checkedAt") { value("2026-10-28T01:02:03Z") }
        }
    }

    @Test
    fun `F-003 나중에 살아난 DB - 조회할 때마다 연결을 다시 확인해 바뀐 상태를 돌려준다`() {
        connector.status =
            failed(ConnectionFailure(ConnectionFailureReason.UNREACHABLE, "DB에 닿지 않습니다", listOf("askflow.target-db.url")))
        mockMvc.get("/api/connection/status").andExpect { jsonPath("$.state") { value("FAILED") } }

        connector.status = connected()
        mockMvc.get("/api/connection/status").andExpect { jsonPath("$.state") { value("CONNECTED") } }

        assertEquals(2, connector.calls)
    }

    @Test
    fun `F-003 비밀 값은 응답에 없음 - 응답에는 정한 필드만 있고 비밀번호 필드가 없다`() {
        val allowed = setOf("state", "tableCount", "termCount", "writePrivileges", "warnings", "failure", "checkedAt")
        val failure =
            ConnectionFailure(ConnectionFailureReason.UNKNOWN, "알 수 없는 오류", listOf("askflow.target-db.url"))

        for (status in listOf(connected(), connected(writePrivileges = listOf("INSERT")), failed(failure))) {
            connector.status = status
            val body = statusBody()
            val keys =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(body)
                    .properties()
                    .map { it.key }
                    .toSet()

            assertTrue(allowed.containsAll(keys), "정하지 않은 필드가 있다: ${keys - allowed}")
            assertFalse(body.contains("password", ignoreCase = true), "응답에 password가 있다: $body")
        }
    }

    @TestConfiguration
    class FakeConnectorConfiguration {
        @Bean
        fun fakeConnector() = FakeConnector()
    }

    class FakeConnector : DataSourceConnector {
        lateinit var status: ConnectionStatus
        var calls = 0

        override fun execute(sql: String): ExecutionOutcome = error("상태 조회는 SQL을 실행하지 않는다")

        override fun checkConnection(): ConnectionStatus {
            calls++
            return status
        }
    }
}
