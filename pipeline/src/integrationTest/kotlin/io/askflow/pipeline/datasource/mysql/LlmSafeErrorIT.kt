package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** DB 오류 원문에 담긴 개인정보 값을 LLM에 보내지 않는다 (F-003, 7.1 검토 막음 3) */
class LlmSafeErrorIT {
    @Test
    fun `F-003 값을 담은 DB 오류 - GTID_SUBSET에 넘긴 개인정보 값은 LLM에 보낼 오류에 없다`() {
        val sql = "SELECT GTID_SUBSET(customer_zip_code_prefix, '') FROM customers WHERE customer_id = 'c1'"

        val outcome = MySqlConnector(TestMySql.config()).execute(sql)

        assertIs<ExecutionOutcome.Failure>(outcome, "결과: $outcome")
        val error = outcome.error
        assertEquals(ExecutionErrorKind.DB_ERROR, error.kind)
        // 화면용 원인에는 MySQL이 넣은 값이 그대로 있다
        assertContains(error.cause, "01001")
        assertNotNull(error.dbErrorCode)
        assertNotNull(error.sqlState)

        val safe = error.forLlm()
        assertEquals(error.dbErrorCode, safe.dbErrorCode)
        assertEquals(error.sqlState, safe.sqlState)
        assertFalse(safe.toString().contains("01001"), "LLM에 보낼 오류에 컬럼 값이 있다: $safe")
        assertFalse(safe.toString().contains(error.cause), "LLM에 보낼 오류에 DB 원문이 있다: $safe")
    }
}
