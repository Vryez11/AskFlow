package io.askflow.api.connection

import io.askflow.pipeline.datasource.TargetDbConfig
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** data-connection "설정 파일로 연결"의 설정 바인딩과 검증 시나리오 (F-003) */
class TargetDbPropertiesTest {
    // 아무것도 듣지 않는 포트. 설정 바인딩만 보고 DB에는 닿지 않는다
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(TargetDbConfiguration::class.java)
            .withPropertyValues(
                "askflow.target-db.url=jdbc:mysql://127.0.0.1:1/olist",
                "askflow.target-db.schema=olist",
                "askflow.target-db.username=askflow_reader",
                "askflow.target-db.password=test-secret-pw",
            )

    private fun configOf(vararg properties: String): TargetDbConfig {
        var config: TargetDbConfig? = null
        runner.withPropertyValues(*properties).run { context ->
            assertTrue(context.startupFailure == null, "시작 실패: ${context.startupFailure}")
            config = context.getBean(TargetDbConfig::class.java)
        }
        return assertNotNull(config)
    }

    private fun assertStartupFailsOn(
        key: String,
        vararg properties: String,
    ) {
        runner.withPropertyValues(*properties).run { context ->
            val failure = assertNotNull(context.startupFailure, "${properties.toList()}인데 시작했다")
            val messages = generateSequence(failure as Throwable) { it.cause }.joinToString("\n") { it.message.orEmpty() }
            assertContains(messages, key, message = "실패 메시지에 잘못된 설정 키가 없다:\n$messages")
        }
    }

    @Test
    fun `F-003 설정한 DB에 연결 - 설정 파일의 값이 커넥터 설정으로 옮겨진다`() {
        val config =
            configOf(
                "askflow.target-db.allowed-tables=orders,customers",
                "askflow.target-db.pii-columns=customers.email,customers.phone",
                "askflow.target-db.max-rows=50",
                "askflow.target-db.query-timeout=3s",
            )

        assertEquals("jdbc:mysql://127.0.0.1:1/olist", config.url)
        assertEquals("olist", config.schema)
        assertEquals("askflow_reader", config.username)
        assertEquals("test-secret-pw", config.password)
        assertEquals(listOf("orders", "customers"), config.allowedTables)
        assertEquals(listOf("customers.email", "customers.phone"), config.piiColumns)
        assertEquals(50, config.maxRows)
        assertEquals(Duration.ofSeconds(3), config.queryTimeout)
    }

    @Test
    fun `F-003 상한을 설정하지 않음 - 행 수 상한은 1000행, 실행 시간 상한은 10초다`() {
        val config = configOf()

        assertEquals(1000, config.maxRows)
        assertEquals(Duration.ofSeconds(10), config.queryTimeout)
    }

    @Test
    fun `F-003 허용 테이블을 설정하지 않음 - 허용 테이블 목록이 비어 스키마 전체를 쓴다`() {
        val config = configOf()

        assertEquals(emptyList(), config.allowedTables)
    }

    @Test
    fun `F-003 잘못된 상한 값 - 행 수 상한이 0이면 시작하지 않고 max-rows 키를 알려 준다`() {
        assertStartupFailsOn("askflow.target-db.max-rows", "askflow.target-db.max-rows=0")
    }

    @Test
    fun `F-003 잘못된 상한 값 - 행 수 상한이 음수면 시작하지 않고 max-rows 키를 알려 준다`() {
        assertStartupFailsOn("askflow.target-db.max-rows", "askflow.target-db.max-rows=-1")
    }

    @Test
    fun `F-003 잘못된 상한 값 - 실행 시간 상한이 0이면 시작하지 않고 query-timeout 키를 알려 준다`() {
        assertStartupFailsOn("askflow.target-db.query-timeout", "askflow.target-db.query-timeout=0s")
    }

    @Test
    fun `F-003 잘못된 상한 값 - 실행 시간 상한이 음수면 시작하지 않고 query-timeout 키를 알려 준다`() {
        assertStartupFailsOn("askflow.target-db.query-timeout", "askflow.target-db.query-timeout=-1s")
    }
}
