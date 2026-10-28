package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 확인하지 못한 출처(LATERAL, 대소문자만 다른 이름)의 열은 실제 MySQL 결과에서도 LLM에 보내지 않는다 (F-003, design 7장 "출처 이름") */
class PiiSourceNameIT {
    private fun execute(sql: String): ExecutionOutcome.Success {
        val outcome = MySqlConnector(TestMySql.config()).execute(sql)
        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        return outcome
    }

    @Test
    fun `F-003 LATERAL 출처의 열 - 앞쪽 개인정보 테이블을 참조한 열이 LLM에 보낼 결과에서 빠진다`() {
        val result =
            execute(
                "SELECT c.customer_id, l.z FROM customers c, LATERAL (SELECT c.customer_zip_code_prefix AS z) AS l " +
                    "ORDER BY c.customer_id",
            ).result

        assertEquals(listOf<Any?>("c1", "01001"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("z"), safe.removedColumns)
    }

    @Test
    fun `F-003 대소문자만 다른 출처의 열 - 테이블과 대소문자만 다른 CTE가 보이면 LLM에 보낼 결과에서 빠진다`() {
        // lower_case_table_names = 0인 이 서버는 orders 테이블을 읽지만, 설정이 다르면 개인정보를 담은 CTE를 읽는다
        val result =
            execute(
                "WITH Orders AS (SELECT customer_zip_code_prefix AS order_id FROM customers) " +
                    "SELECT o.order_id, (SELECT COUNT(*) FROM customers) AS n FROM orders o ORDER BY o.order_id",
            ).result

        assertEquals(listOf<Any?>("o1", 4L), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("n"), safe.columns)
        assertEquals(listOf("order_id"), safe.removedColumns)
    }
}
