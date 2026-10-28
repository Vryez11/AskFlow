package io.askflow.pipeline.datasource.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** LIMIT 다시 쓰기에서 상한을 피해 가거나 LIMIT이 두 번 찍히는 경우가 없는지 본다 (F-003) */
class LimitRewriteEdgeTest {
    private fun rewrite(sql: String): String {
        val result = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = 1000).check(sql)
        assertIs<GuardResult.Passed>(result, "통과해야 하는 SQL: $sql")
        return result.sql
    }

    private fun limitCount(sql: String): Int = Regex("\\bLIMIT\\b", RegexOption.IGNORE_CASE).findAll(sql).count()

    @Test
    fun `F-003 상한보다 큰 LIMIT - 괄호 없는 UNION 끝 LIMIT은 한 번만 찍힌다`() {
        val sql = rewrite("SELECT customer_id FROM orders UNION SELECT customer_id FROM customers LIMIT 100000")
        assertEquals(1, limitCount(sql), sql)
        assertEquals(true, sql.trimEnd().endsWith("LIMIT 1001"), sql)
    }

    @Test
    fun `F-003 상한 안의 결과 - 괄호 친 UNION에도 바깥 LIMIT을 붙인다`() {
        val sql = rewrite("(SELECT customer_id FROM orders LIMIT 5) UNION (SELECT customer_id FROM customers)")
        assertEquals(true, sql.trimEnd().endsWith("LIMIT 1001"), sql)
    }

    @Test
    fun `F-003 상한보다 큰 LIMIT - 아주 큰 수도 상한으로 줄인다`() {
        val sql = rewrite("SELECT order_id FROM orders LIMIT 18446744073709551615")
        assertEquals(true, sql.trimEnd().endsWith("LIMIT 1001"), sql)
    }

    @Test
    fun `F-003 상한 안의 결과 - 괄호로 감싼 최상위 SELECT에도 LIMIT을 붙인다`() {
        val sql = rewrite("(SELECT order_id FROM orders)")
        assertEquals(true, sql.trimEnd().endsWith("LIMIT 1001"), sql)
    }
}
