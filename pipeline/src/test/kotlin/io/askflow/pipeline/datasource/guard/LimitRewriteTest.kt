package io.askflow.pipeline.datasource.guard

import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** safe-sql-execution "행 수 상한"의 LIMIT 다시 쓰기 (F-003). 상한을 넘었는지 알도록 maxRows + 1을 붙인다 */
class LimitRewriteTest {
    private fun rewrite(
        sql: String,
        maxRows: Int = 1000,
    ): Select {
        val result = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = maxRows).check(sql)
        assertIs<GuardResult.Passed>(result, "통과해야 하는 SQL: $sql")
        val statement = CCJSqlParserUtil.parse(result.sql)
        assertIs<Select>(statement, "다시 쓴 SQL: ${result.sql}")
        return statement
    }

    // 괄호 없는 UNION 끝의 LIMIT은 MySQL에서 전체에 걸리지만, JSqlParser는 마지막 SELECT에 붙인다
    private fun Select.topLimit(): Select =
        if (this is SetOperationList && limit == null && selects.last() is PlainSelect) selects.last() else this

    private fun Select.limitValue(): Long? = (topLimit().limit?.rowCount as LongValue?)?.value

    private fun Select.offsetValue(): Long? = topLimit().let { (it.limit?.offset ?: it.offset?.offset) as LongValue? }?.value

    @Test
    fun `F-003 상한 안의 결과 - LIMIT이 없으면 상한보다 하나 많은 LIMIT을 붙인다`() {
        assertEquals(1001, rewrite("SELECT order_id FROM orders").limitValue())
    }

    @Test
    fun `F-003 상한보다 작은 LIMIT - 그대로 둔다`() {
        assertEquals(10, rewrite("SELECT order_id FROM orders LIMIT 10").limitValue())
    }

    @Test
    fun `F-003 상한보다 큰 LIMIT - 상한보다 하나 많은 값으로 줄인다`() {
        assertEquals(1001, rewrite("SELECT order_id FROM orders LIMIT 100000").limitValue())
    }

    @Test
    fun `F-003 상한을 설정으로 바꿈 - 상한 50이면 LIMIT 51을 쓴다`() {
        assertEquals(51, rewrite("SELECT order_id FROM orders", maxRows = 50).limitValue())
        assertEquals(51, rewrite("SELECT order_id FROM orders LIMIT 80", maxRows = 50).limitValue())
        assertEquals(30, rewrite("SELECT order_id FROM orders LIMIT 30", maxRows = 50).limitValue())
    }

    @Test
    fun `F-003 상한보다 큰 LIMIT - OFFSET은 그대로 둔다`() {
        val withOffset = rewrite("SELECT order_id FROM orders LIMIT 100000 OFFSET 20")
        assertEquals(1001, withOffset.limitValue())
        assertEquals(20, withOffset.offsetValue())

        val mysqlStyle = rewrite("SELECT order_id FROM orders LIMIT 20, 100000")
        assertEquals(1001, mysqlStyle.limitValue())
        assertEquals(20, mysqlStyle.offsetValue())
    }

    @Test
    fun `F-003 상한 안의 결과 - CTE와 UNION도 최상위 쿼리에 LIMIT을 붙인다`() {
        assertEquals(
            1001,
            rewrite("WITH m AS (SELECT order_id FROM orders) SELECT order_id FROM m").limitValue(),
        )
        assertEquals(
            1001,
            rewrite("SELECT customer_id FROM orders UNION SELECT customer_id FROM customers").limitValue(),
        )
        assertEquals(
            1001,
            rewrite("SELECT customer_id FROM orders UNION ALL SELECT customer_id FROM customers LIMIT 100000")
                .limitValue(),
        )
    }

    @Test
    fun `F-003 상한 안의 결과 - 서브쿼리의 LIMIT은 최상위 상한을 대신하지 않는다`() {
        assertEquals(
            1001,
            rewrite("SELECT order_id FROM (SELECT order_id FROM orders LIMIT 5) t").limitValue(),
        )
    }
}
