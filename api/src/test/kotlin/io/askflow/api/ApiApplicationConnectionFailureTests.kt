package io.askflow.api

import io.askflow.pipeline.datasource.TargetDbConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * data-connection "연결 실패에도 앱은 뜬다" (F-003). `application.yml`의 기본값으로 앱 전체를 띄운다.
 * MYSQL_PORT=1은 아무것도 듣지 않는 포트라, 분석 대상 DB가 꺼진 것과 같다.
 * 비밀번호는 비밀 값 검사에 걸리지 않게 실행마다 만들어 넣는다.
 */
@SpringBootTest(properties = ["MYSQL_PORT=1"])
@AutoConfigureMockMvc
class ApiApplicationConnectionFailureTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var config: TargetDbConfig

    @Test
    fun `F-003 DB에 닿지 않음 - 앱은 뜨고 연결 상태는 연결 실패, 접속 불가, 접속 주소를 확인하게 한다`() {
        mockMvc.get("/api/connection/status").andExpect {
            status { isOk() }
            jsonPath("$.state") { value("FAILED") }
            jsonPath("$.failure.reason") { value("UNREACHABLE") }
            jsonPath("$.failure.checkKeys[0]") { value("askflow.target-db.url") }
        }
    }

    @Test
    fun `F-003 비밀 값은 응답에 없음 - 연결 실패 응답 어디에도 비밀번호 값이 없다`() {
        val body =
            mockMvc
                .get("/api/connection/status")
                .andReturn()
                .response
                .contentAsString

        assertFalse(body.contains(PASSWORD), "응답에 비밀번호가 있다: $body")
    }

    @Test
    fun `F-003 설정한 DB에 연결 - 기본 설정은 olist 스키마, 읽기 계정, 비밀번호 환경 변수를 쓴다`() {
        assertEquals("jdbc:mysql://localhost:1/olist", config.url)
        assertEquals("olist", config.schema)
        assertEquals("askflow_reader", config.username)
        assertEquals(PASSWORD, config.password)
    }

    @Test
    fun `F-003 상한을 설정하지 않음 - 기본 설정의 상한은 1000행, 10초이고 허용 테이블은 비어 있다`() {
        assertEquals(1000, config.maxRows)
        assertEquals(Duration.ofSeconds(10), config.queryTimeout)
        assertEquals(emptyList(), config.allowedTables)
    }

    companion object {
        val PASSWORD = UUID.randomUUID().toString()

        @JvmStatic
        @DynamicPropertySource
        fun readerPassword(registry: DynamicPropertyRegistry) {
            registry.add("ASKFLOW_READER_PASSWORD") { PASSWORD }
        }
    }
}
