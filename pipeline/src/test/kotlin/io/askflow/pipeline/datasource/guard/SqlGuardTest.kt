package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.RecordingDataSource
import io.askflow.pipeline.datasource.TargetDbConfig
import io.askflow.pipeline.datasource.mysql.MySqlConnector
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** safe-sql-execution의 SQL 검사 시나리오 (F-003) */
class SqlGuardTest {
    private val guard = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = 1000)

    private fun passed(sql: String): String {
        val result = guard.check(sql)
        assertIs<GuardResult.Passed>(result, "통과해야 하는 SQL: $sql")
        return result.sql
    }

    private fun rejected(sql: String): ExecutionError {
        val result = guard.check(sql)
        assertIs<GuardResult.Rejected>(result, "거부해야 하는 SQL: $sql")
        return result.error
    }

    private fun assertRejectedAs(
        kind: ExecutionErrorKind,
        sql: String,
    ): ExecutionError {
        val error = rejected(sql)
        assertEquals(kind, error.kind, "SQL: $sql")
        return error
    }

    // 실행되는 주석과 힌트 거부

    @Test
    fun `F-003 실행 주석 - SELECT가 아닌 SQL로 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.NOT_SELECT, "SELECT 1 /*!50000 , SLEEP(10) */")
    }

    @Test
    fun `F-003 실행 시간 힌트 - SELECT가 아닌 SQL로 거부된다`() {
        assertRejectedAs(
            ExecutionErrorKind.NOT_SELECT,
            "SELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
        )
    }

    @Test
    fun `F-003 보통 주석 - 통과하고 DB에 보내는 문장에는 주석이 없다`() {
        val sql = passed("-- 주문 수\nSELECT COUNT(*) /* 전체 */ FROM orders")

        assertFalse(sql.contains("--"), "다시 쓴 SQL: $sql")
        assertFalse(sql.contains("/*"), "다시 쓴 SQL: $sql")
        assertFalse(sql.contains("주문 수"), "다시 쓴 SQL: $sql")
    }

    @Test
    fun `F-003 보통 주석 - 문자열 리터럴 안의 실행 주석 모양은 거부하지 않는다`() {
        passed("SELECT '/*!50000 x */' AS s FROM orders")
    }

    // SELECT 문 하나만 허용

    @Test
    fun `F-003 단순 SELECT - 검사를 통과한다`() {
        passed("SELECT order_status, COUNT(*) FROM orders GROUP BY order_status")
    }

    @Test
    fun `F-003 CTE와 UNION - 검사를 통과한다`() {
        passed(
            "WITH monthly AS (SELECT order_id FROM orders WHERE order_status = 'delivered') " +
                "SELECT COUNT(*) FROM monthly",
        )
        passed("SELECT customer_id FROM orders UNION SELECT customer_id FROM customers")
        passed("(SELECT order_id FROM orders) UNION ALL (SELECT customer_id FROM customers)")
    }

    @Test
    fun `F-003 쓰기 문 - INSERT UPDATE DELETE REPLACE는 SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "INSERT INTO orders (order_id) VALUES ('x')",
            "UPDATE orders SET order_status = 'canceled'",
            "DELETE FROM orders",
            "REPLACE INTO orders (order_id) VALUES ('x')",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 DDL - CREATE ALTER DROP TRUNCATE는 SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "CREATE TABLE copied (id INT)",
            "ALTER TABLE orders ADD COLUMN memo VARCHAR(10)",
            "DROP TABLE orders",
            "TRUNCATE TABLE orders",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 쓰기 문 - CALL과 SET도 SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "CALL refresh_orders()",
            "SET SESSION max_execution_time = 0",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 여러 문 - 여러 문 오류로 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.MULTIPLE_STATEMENTS, "SELECT 1; DELETE FROM orders")
        assertRejectedAs(ExecutionErrorKind.MULTIPLE_STATEMENTS, "SELECT 1; SELECT 2")
    }

    @Test
    fun `F-003 잠금이나 파일 쓰기가 붙은 SELECT - SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "SELECT * FROM orders FOR UPDATE",
            "SELECT * FROM orders FOR SHARE",
            "SELECT * FROM orders LOCK IN SHARE MODE",
            "SELECT * FROM orders INTO OUTFILE '/tmp/orders.csv'",
            "SELECT * INTO OUTFILE '/tmp/orders.csv' FROM orders",
            "SELECT * INTO DUMPFILE '/tmp/orders.bin' FROM orders",
            "SELECT COUNT(*) INTO @order_count FROM orders",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 해석할 수 없는 SQL - SQL 해석 실패로 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.PARSE_FAILED, "SELEC order_id FRM orders")
        assertRejectedAs(ExecutionErrorKind.PARSE_FAILED, "SELECT order_id FROM orders WHERE")
    }

    // 허용 테이블만 조회

    @Test
    fun `F-003 허용 테이블만 씀 - 조인은 검사를 통과한다`() {
        passed(
            "SELECT c.customer_state, COUNT(*) FROM orders o " +
                "JOIN customers c ON o.customer_id = c.customer_id GROUP BY c.customer_state",
        )
    }

    @Test
    fun `F-003 허용 테이블만 씀 - 대소문자와 설정 스키마 이름은 상관없다`() {
        passed("SELECT COUNT(*) FROM ORDERS")
        passed("SELECT COUNT(*) FROM olist.orders")
        passed("SELECT COUNT(*) FROM `olist`.`orders`")
    }

    @Test
    fun `F-003 허용하지 않은 테이블 - 서브쿼리에서 읽으면 거부되고 오류에 테이블 이름이 있다`() {
        val error =
            assertRejectedAs(
                ExecutionErrorKind.TABLE_NOT_ALLOWED,
                "SELECT order_id FROM orders WHERE customer_id IN (SELECT customer_id FROM sellers)",
            )

        assertContains(error.cause, "sellers")
    }

    @Test
    fun `F-003 허용하지 않은 테이블 - 조인과 CTE 본문에서 읽어도 거부된다`() {
        assertRejectedAs(
            ExecutionErrorKind.TABLE_NOT_ALLOWED,
            "SELECT o.order_id FROM orders o JOIN sellers s ON o.order_id = s.seller_id",
        )
        assertRejectedAs(
            ExecutionErrorKind.TABLE_NOT_ALLOWED,
            "WITH s AS (SELECT seller_id FROM sellers) SELECT * FROM s",
        )
    }

    @Test
    fun `F-003 다른 스키마 - 시스템 스키마와 서비스 DB 테이블은 거부된다`() {
        listOf(
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM mysql.user",
            "SELECT * FROM askflow.artifact",
            "SELECT * FROM other.orders",
        ).forEach { assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, it) }
    }

    @Test
    fun `F-003 CTE 이름 - CTE 이름은 테이블로 보지 않고 통과한다`() {
        passed(
            "WITH monthly AS (SELECT DATE_FORMAT(order_purchase_timestamp, '%Y-%m') AS ym FROM orders) " +
                "SELECT * FROM monthly",
        )
    }

    // 부작용이 있는 함수 거부

    @Test
    fun `F-003 SLEEP - 허용하지 않은 함수로 거부되고 오류에 함수 이름이 있다`() {
        val error = assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT SLEEP(100)")

        assertContains(error.cause, "SLEEP", ignoreCase = true)
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "select sleep(1)")
        assertRejectedAs(
            ExecutionErrorKind.FUNCTION_NOT_ALLOWED,
            "SELECT order_id FROM orders WHERE order_id = (SELECT SLEEP(1))",
        )
    }

    @Test
    fun `F-003 LOAD_FILE - 허용하지 않은 함수로 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT LOAD_FILE('/etc/passwd')")
    }

    @Test
    fun `F-003 부작용이 있는 함수 - 거부 목록의 함수는 모두 거부된다`() {
        listOf(
            "SELECT BENCHMARK(1000000, MD5('a'))",
            "SELECT GET_LOCK('a', 10)",
            "SELECT RELEASE_LOCK('a')",
            "SELECT RELEASE_ALL_LOCKS()",
        ).forEach { assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, it) }
    }

    // 검사를 거친 SQL만 실행

    @Test
    fun `F-003 검사에 걸린 SQL은 DB에 닿지 않음 - 아무 문장도 보내지 않고 검사 오류를 돌려준다`() {
        val dataSource = RecordingDataSource()
        val connector =
            MySqlConnector(
                TargetDbConfig(
                    url = "jdbc:mysql://fake:3306/olist",
                    schema = "olist",
                    username = "askflow_reader",
                    password = "secret",
                    allowedTables = listOf("orders", "customers"),
                ),
                dataSource,
            )
        val cases =
            mapOf(
                "DELETE FROM orders" to ExecutionErrorKind.NOT_SELECT,
                "SELECT 1; DELETE FROM orders" to ExecutionErrorKind.MULTIPLE_STATEMENTS,
                "SELECT 1 /*!50000 , SLEEP(10) */" to ExecutionErrorKind.NOT_SELECT,
                "SELEC 1" to ExecutionErrorKind.PARSE_FAILED,
                "SELECT * FROM sellers" to ExecutionErrorKind.TABLE_NOT_ALLOWED,
                "SELECT SLEEP(100)" to ExecutionErrorKind.FUNCTION_NOT_ALLOWED,
            )

        cases.forEach { (sql, kind) ->
            val outcome = connector.execute(sql)
            assertIs<ExecutionOutcome.Failure>(outcome, "SQL: $sql")
            assertEquals(kind, outcome.error.kind, "SQL: $sql")
        }
        assertTrue(dataSource.sentSql.isEmpty(), "DB에 보낸 문장: ${dataSource.sentSql}")
    }
}
