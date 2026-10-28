package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 서브쿼리의 같은 별칭으로 개인정보 열 가리기를 피하지 못한다 (F-003, 7.1 검토 막음 2) */
class PiiShadowAliasIT {
    @Test
    fun `F-003 서브쿼리를 거친 열 - 안쪽 테이블에 바깥 개인정보 테이블의 별칭을 붙여도 LLM에 보낼 결과에서 빠진다`() {
        val sql =
            """
            SELECT c.customer_id,
                   (SELECT c.customer_zip_code_prefix FROM orders c LIMIT 1) AS v
            FROM customers c ORDER BY c.customer_id
            """.trimIndent()

        val outcome = MySqlConnector(TestMySql.config()).execute(sql)

        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        val result = outcome.result
        // MySQL이 안쪽 orders에 없는 컬럼을 바깥 customers에서 찾는다
        assertEquals(listOf<Any?>("c1", "01001"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("v"), safe.removedColumns)
    }
}
