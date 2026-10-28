package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 안전이 확인된 열만 LLM에 보낼 결과에 남는다 (F-003, design 7장 "판정 방향") */
class PiiAllowListIT {
    private fun succeeded(sql: String): QueryResult {
        val outcome = MySqlConnector(TestMySql.config()).execute(sql)
        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        return outcome.result
    }

    @Test
    fun `F-003 허용 목록 안의 함수 - 개인정보가 아닌 컬럼을 내장 함수와 SUM으로 계산한 열은 남는다`() {
        val result = succeeded("SELECT UPPER(order_status) AS s, SUM(amount) AS total FROM orders GROUP BY s")

        assertEquals(listOf("s", "total"), result.forLlm().columns)
    }

    @Test
    fun `F-003 허용 목록 밖의 함수 - 재료와 상관없이 그 열은 빠진다`() {
        val result = succeeded("SELECT order_id, MD5(order_status) AS h FROM orders ORDER BY order_id")

        assertEquals(listOf("order_id"), result.forLlm().columns)
        assertEquals(listOf("h"), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 확인하지 못한 이름 - 실행할 때 읽은 컬럼 목록에서 대소문자만 다른 이름은 찾아 남긴다`() {
        val result = succeeded("SELECT o.ORDER_ID, Order_Status FROM orders o ORDER BY order_id")

        assertEquals(listOf("ORDER_ID", "Order_Status"), result.forLlm().columns)
    }
}
