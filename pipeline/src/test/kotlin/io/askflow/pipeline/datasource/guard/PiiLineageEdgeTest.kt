package io.askflow.pipeline.datasource.guard

import kotlin.test.Test
import kotlin.test.assertEquals

/** 개인정보 열 추적이 놓치기 쉬운 길. 풀지 못하면 가리는지 본다 (F-003) */
class PiiLineageEdgeTest {
    private val lineage = PiiLineage(piiColumns = listOf("customers.customer_zip_code_prefix"))

    private fun assertTrace(
        expected: List<ItemPii>,
        sql: String,
    ) {
        assertEquals(expected, lineage.trace(sql), "SQL: $sql")
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 열 이름 목록을 붙인 CTE와 파생 테이블도 자리로 이어 간다`() {
        assertTrace(
            listOf(ItemPii.PII, ItemPii.SAFE),
            "WITH c(z, s) AS (SELECT customer_zip_code_prefix, customer_state FROM customers) SELECT z, s FROM c",
        )
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT t.a FROM (SELECT customer_zip_code_prefix FROM customers) AS t(a)",
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 괄호로 묶은 조인과 상관 서브쿼리도 따라간다`() {
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT c.customer_zip_code_prefix FROM (orders o JOIN customers c ON o.customer_id = c.customer_id)",
        )
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT (SELECT c.customer_zip_code_prefix FROM orders o WHERE o.customer_id = c.customer_id LIMIT 1) " +
                "FROM customers c",
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 식 안 어디에 있어도 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT CASE WHEN customer_state = 'SP' THEN customer_zip_code_prefix END FROM customers")
        assertTrace(listOf(ItemPii.PII), "SELECT MAX(customer_zip_code_prefix) OVER (PARTITION BY customer_state) FROM customers")
        assertTrace(listOf(ItemPii.PII), "SELECT COUNT(*) OVER (PARTITION BY customer_zip_code_prefix) FROM customers")
        assertTrace(listOf(ItemPii.SAFE), "SELECT COUNT((SELECT MAX(customer_zip_code_prefix) FROM customers))")
    }

    @Test
    fun `F-003 어디서 온 열인지 알 수 없음 - 서브쿼리를 펼친 별표와 별표가 섞인 UNION은 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT * FROM (SELECT customer_zip_code_prefix AS z FROM customers) t")
        assertTrace(listOf(ItemPii.PII), "WITH c AS (SELECT * FROM customers) SELECT c.* FROM c")
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT customer_state FROM customers UNION SELECT * FROM customers",
        )
    }

    @Test
    fun `F-003 어디서 온 열인지 알 수 없음 - 모르는 별칭과 재귀 CTE의 자기 참조는 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT x.customer_state FROM customers c")
        assertTrace(
            listOf(ItemPii.PII),
            "WITH RECURSIVE r AS (SELECT customer_zip_code_prefix AS z FROM customers UNION ALL SELECT z FROM r) SELECT z FROM r",
        )
    }

    @Test
    fun `F-003 개인정보 컬럼을 그대로 고름 - 같은 이름이라도 개인정보가 아닌 테이블과 CTE의 안전한 열은 남긴다`() {
        assertTrace(listOf(ItemPii.SAFE), "SELECT o.customer_zip_code_prefix FROM orders o")
        assertTrace(
            listOf(ItemPii.SAFE),
            "WITH customers AS (SELECT customer_state AS customer_zip_code_prefix FROM olist.customers) " +
                "SELECT customer_zip_code_prefix FROM customers",
        )
    }
}
