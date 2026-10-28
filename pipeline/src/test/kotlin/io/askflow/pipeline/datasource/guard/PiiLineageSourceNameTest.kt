package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.ItemPii.PII
import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import kotlin.test.Test
import kotlin.test.assertEquals

/** 출처 이름을 확인하지 못하면 그 출처의 열을 모두 가린다: LATERAL, 대소문자만 다른 테이블·CTE 이름 (F-003, design 7장 "출처 이름") */
class PiiLineageSourceNameTest {
    private val objects =
        PiiSchemaObjects(
            views = emptyList(),
            generatedColumns = emptyList(),
            functions = emptyList(),
            columns =
                listOf(
                    "orders" to "order_id",
                    "orders" to "customer_zip_code_prefix",
                    "customers" to "customer_id",
                    "customers" to "customer_zip_code_prefix",
                ),
        )
    private val lineage = PiiLineage(listOf("customers.customer_zip_code_prefix"), objects)

    private fun assertTrace(
        expected: List<ItemPii>,
        sql: String,
    ) {
        assertEquals(expected, lineage.trace(sql), "SQL: $sql")
    }

    @Test
    fun `F-003 LATERAL 파생 테이블 - 앞쪽 형제 출처를 참조한 열은 확인하지 못한 것으로 보아 가린다`() {
        // MySQL은 LATERAL 안의 c를 같은 FROM의 customers c로 찾는다. 바깥의 orders c가 아니다
        assertTrace(
            listOf(PII),
            "SELECT (SELECT l.z FROM customers c JOIN LATERAL (SELECT c.customer_zip_code_prefix AS z) AS l ON TRUE) AS x " +
                "FROM orders c",
        )
        assertTrace(listOf(PII), "SELECT l.z FROM orders o, LATERAL (SELECT o.order_id AS z) AS l")
    }

    @Test
    fun `F-003 대소문자만 다른 테이블 이름 - 실행 때 읽은 목록과 정확히 같지 않으면 그 출처의 열을 모두 가린다`() {
        assertTrace(listOf(SAFE), "SELECT order_id FROM orders")
        assertTrace(listOf(PII), "SELECT order_id FROM Orders")
        assertTrace(listOf(PII), "SELECT o.order_id FROM ORDERS o")
    }

    @Test
    fun `F-003 대소문자만 다른 CTE 이름 - CTE와 정확히 같지 않으면 그 출처의 열을 모두 가린다`() {
        assertTrace(listOf(SAFE), "WITH t AS (SELECT order_id FROM orders) SELECT order_id FROM t")
        assertTrace(listOf(PII), "WITH t AS (SELECT order_id FROM orders) SELECT order_id FROM T")
        // 서버 설정에 따라 orders 테이블로도, CTE로도 읽힐 수 있다
        assertTrace(
            listOf(PII),
            "WITH Orders AS (SELECT customer_zip_code_prefix AS order_id FROM customers) SELECT order_id FROM orders",
        )
    }
}
