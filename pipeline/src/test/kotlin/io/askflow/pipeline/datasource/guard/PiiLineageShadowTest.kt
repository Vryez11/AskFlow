package io.askflow.pipeline.datasource.guard

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 서브쿼리가 바깥 개인정보 테이블과 같은 별칭을 써도 개인정보 열을 놓치지 않는다 (F-003, 7.1 검토 막음 2).
 * MySQL은 안쪽 테이블에 없는 컬럼을 바깥 범위의 같은 별칭에서 찾는다.
 */
class PiiLineageShadowTest {
    private val lineage =
        PiiLineage(
            piiColumns = listOf("customers.customer_zip_code_prefix", "customers.customer_unique_id"),
        )

    private fun assertTrace(
        expected: List<ItemPii>,
        sql: String,
    ) {
        assertEquals(expected, lineage.trace(sql), "SQL: $sql")
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 안쪽 테이블에 같은 별칭을 붙여도 바깥 개인정보 컬럼이면 가린다`() {
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT (SELECT c.customer_zip_code_prefix FROM orders c LIMIT 1) AS v FROM customers c",
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 조인한 바깥 테이블과 같은 별칭으로 바깥 개인정보 컬럼을 꺼내도 가린다`() {
        assertTrace(
            listOf(ItemPii.SAFE, ItemPii.PII),
            """
            SELECT x.order_id,
                   (SELECT MAX(o.customer_unique_id) FROM orders o WHERE o.order_id = 'o1') AS v
            FROM orders x JOIN customers o ON x.customer_id = o.customer_id
            """.trimIndent(),
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 같은 별칭이어도 어느 쪽도 개인정보가 아니면 가리지 않는다`() {
        assertTrace(
            listOf(ItemPii.SAFE),
            "SELECT (SELECT o.order_status FROM orders o LIMIT 1) AS v FROM orders o",
        )
    }
}
