package io.askflow.pipeline.datasource

import io.askflow.pipeline.datasource.guard.GuardResult
import io.askflow.pipeline.datasource.guard.SqlGuard
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** LLM에 보낼 실행 오류 (F-003, safe-sql-execution "LLM에 보낼 실행 오류") */
class LlmSafeErrorTest {
    private val rawMessage = "Malformed GTID set specification '01001'."

    private val dbError =
        ExecutionError(
            kind = ExecutionErrorKind.DB_ERROR,
            message = "DB가 SQL을 실행하지 못했습니다.",
            cause = rawMessage,
            checkPoints = listOf("실행하려던 SQL"),
            dbErrorCode = 1772,
            sqlState = "HY000",
        )

    @Test
    fun `F-003 값을 담은 DB 오류 - LLM에 보낼 오류에는 종류·메시지·오류 코드·SQLState만 있고 값과 원문이 없다`() {
        val safe = dbError.forLlm()

        assertEquals(ExecutionErrorKind.DB_ERROR, safe.kind)
        assertEquals("DB 오류", safe.kind.label)
        assertEquals("DB가 SQL을 실행하지 못했습니다.", safe.message)
        assertEquals(1772, safe.dbErrorCode)
        assertEquals("HY000", safe.sqlState)
        assertNull(safe.cause)
        val text = safe.toString()
        assertContains(text, "DB 오류")
        assertContains(text, "1772")
        assertContains(text, "HY000")
        assertFalse(text.contains("01001"), "LLM에 보낼 오류에 컬럼 값이 있다: $text")
        assertFalse(text.contains("Malformed GTID"), "LLM에 보낼 오류에 DB 원문이 있다: $text")
    }

    @Test
    fun `F-003 화면용 오류는 그대로 - LLM에 보낼 오류를 만들어도 실행 오류의 원인에는 DB 원문이 남는다`() {
        dbError.forLlm()

        assertEquals(rawMessage, dbError.cause)
        assertEquals(1772, dbError.dbErrorCode)
    }

    @Test
    fun `F-003 검사에 걸린 오류 - LLM에 보낼 오류에 허용하지 않은 테이블과 그 이름이 남는다`() {
        val rejected = SqlGuard("olist", setOf("orders"), 1000).check("SELECT seller_id FROM sellers")
        assertIs<GuardResult.Rejected>(rejected)

        val safe = rejected.error.forLlm()

        assertEquals(ExecutionErrorKind.TABLE_NOT_ALLOWED, safe.kind)
        assertEquals("sellers", safe.cause)
        val text = safe.toString()
        assertContains(text, "허용하지 않은 테이블")
        assertContains(text, "sellers")
    }
}
