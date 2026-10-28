package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.ItemPii.PII
import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import kotlin.test.Test
import kotlin.test.assertEquals

/** 안전이 확인된 열만 남긴다: 내장 함수 허용 목록과 실행할 때 읽은 컬럼 목록 (F-003, design 7장 "판정 방향") */
class PiiLineageAllowListTest {
    private val objects =
        PiiSchemaObjects(
            views = emptyList(),
            generatedColumns = emptyList(),
            functions = emptyList(),
            columns =
                listOf(
                    "orders" to "order_id",
                    "orders" to "order_status",
                    "orders" to "order_purchase_timestamp",
                    "orders" to "amount",
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
    fun `F-003 허용 목록 안의 함수 - 날짜 문자열 숫자 함수와 SUM AVG로 계산한 열은 남는다`() {
        assertTrace(
            listOf(SAFE, SAFE, SAFE, SAFE, SAFE),
            "SELECT DATE_FORMAT(order_purchase_timestamp, '%Y-%m'), UPPER(order_status), ROUND(amount, 1), " +
                "SUM(amount), AVG(amount) FROM orders GROUP BY 1, 2, 3",
        )
        assertTrace(
            listOf(SAFE, SAFE, SAFE),
            "SELECT TIMESTAMPDIFF(DAY, order_purchase_timestamp, '2018-12-31'), " +
                "ROW_NUMBER() OVER (PARTITION BY order_status ORDER BY amount), " +
                "COUNT(*) OVER (PARTITION BY order_status) FROM orders",
        )
    }

    @Test
    fun `F-003 허용 목록 밖의 함수 - 재료와 상관없이 그 열은 빠진다`() {
        assertTrace(listOf(PII, PII, PII), "SELECT MD5(order_status), USER(), JSON_OBJECT('s', order_status) FROM orders")
    }

    @Test
    fun `F-003 확인하지 못한 이름 - 컬럼 목록에 없는 이름으로 쓴 열은 빠진다`() {
        assertTrace(listOf(SAFE, PII), "SELECT order_id, ordér_status FROM orders")
        assertTrace(listOf(PII), "SELECT o.no_such_column FROM orders o")
        assertTrace(listOf(PII), "SELECT customer_id FROM sellers")
    }

    @Test
    fun `F-003 확인하지 못한 이름 - 해석 트리에서 처음 보는 노드는 안전하지 않다`() {
        assertTrace(listOf(PII), "SELECT @@hostname")
        assertTrace(listOf(PII), "SELECT CONVERT(order_status USING utf8mb4) FROM orders")
    }

    @Test
    fun `F-003 확인하지 못한 이름 - 이름 붙인 창은 그 정의까지 본다`() {
        assertTrace(
            listOf(SAFE),
            "SELECT RANK() OVER w FROM orders WINDOW w AS (ORDER BY amount)",
        )
        assertTrace(
            listOf(PII),
            "SELECT RANK() OVER w FROM customers WINDOW w AS (ORDER BY customer_zip_code_prefix)",
        )
    }
}
