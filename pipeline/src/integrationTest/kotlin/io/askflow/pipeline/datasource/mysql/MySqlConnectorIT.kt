package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.QueryResult
import io.askflow.pipeline.datasource.TargetDbConfig
import java.sql.SQLException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** safe-sql-execution에서 MySQL이 있어야 확인할 수 있는 시나리오 (F-003) */
class MySqlConnectorIT {
    private fun execute(
        sql: String,
        config: TargetDbConfig = TestMySql.config(),
    ) = MySqlConnector(config).execute(sql)

    private fun succeeded(
        sql: String,
        config: TargetDbConfig = TestMySql.config(),
    ): QueryResult {
        val outcome = execute(sql, config)
        assertIs<ExecutionOutcome.Success>(outcome, "성공해야 하는 SQL: $sql, 결과: $outcome")
        return outcome.result
    }

    private fun failed(
        kind: ExecutionErrorKind,
        sql: String,
        config: TargetDbConfig = TestMySql.config(),
    ): ExecutionError {
        val outcome = execute(sql, config)
        assertIs<ExecutionOutcome.Failure>(outcome, "실패해야 하는 SQL: $sql, 결과: $outcome")
        assertEquals(kind, outcome.error.kind, "SQL: $sql, 오류: ${outcome.error}")
        return outcome.error
    }

    private fun countOrdersAsRoot(): Long =
        TestMySql.root { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM orders").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    // 읽기 전용 세션

    @Test
    fun `F-003 검사를 거치지 않은 쓰기 - 쓰기 권한이 있는 계정이어도 세션이 읽기 전용이라 DB가 거부한다`() {
        val before = countOrdersAsRoot()
        val pool =
            MySqlConnector.createPool(
                TestMySql.config(username = TestMySql.WRITER, password = TestMySql.WRITER_PASSWORD),
            )

        val error =
            assertFailsWith<SQLException> {
                pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "INSERT INTO orders VALUES ('x1', 'c1', 'delivered', '2018-04-01 00:00:00', 1.00)",
                        )
                    }
                    connection.commit()
                }
            }

        // 1792: Cannot execute statement in a READ ONLY transaction
        assertEquals(1792, error.errorCode, "DB 오류: ${error.message}")
        assertEquals(before, countOrdersAsRoot())
    }

    // 실행 시간 상한

    @Test
    fun `F-003 실행 시간 상한을 넘는 쿼리 - 중단되고 상한 1초와 설정 키가 담긴다`() {
        val config = TestMySql.config(queryTimeout = Duration.ofSeconds(1))
        val started = System.nanoTime()

        val error = failed(ExecutionErrorKind.TIMEOUT, "SELECT COUNT(*) FROM numbers a, numbers b, numbers c", config)

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofSeconds(5), "상한 1초인데 ${elapsed.toMillis()}ms 걸렸다")
        assertContains(error.cause, "1초")
        assertContains(error.checkPoints, "askflow.target-db.query-timeout")
    }

    @Test
    fun `F-003 실행 시간 상한 안의 쿼리 - 결과가 돌아오고 걸린 시간이 있다`() {
        val result = succeeded("SELECT COUNT(*) AS cnt FROM orders")

        assertEquals(listOf(listOf<Any?>(6L)), result.rows)
        assertFalse(result.elapsed.isNegative || result.elapsed.isZero, "걸린 시간: ${result.elapsed}")
    }

    // 행 수 상한

    @Test
    fun `F-003 상한 안의 결과 - LIMIT 없는 SQL의 30행이 돌아온다`() {
        val result = succeeded("SELECT n FROM numbers WHERE n <= 30 ORDER BY n")

        assertEquals(30, result.rowCount)
        assertEquals((1..30).map { listOf<Any?>(it) }, result.rows)
    }

    @Test
    fun `F-003 상한을 넘는 결과 - 행 없이 상한 1000과 설정 키가 담긴 오류가 돌아온다`() {
        val error = failed(ExecutionErrorKind.ROW_LIMIT_EXCEEDED, "SELECT n FROM numbers")

        assertContains(error.cause, "1000")
        assertContains(error.checkPoints, "askflow.target-db.max-rows")
    }

    @Test
    fun `F-003 상한보다 작은 LIMIT - LIMIT 10이 그대로 적용된다`() {
        val result = succeeded("SELECT n FROM numbers ORDER BY n LIMIT 10")

        assertEquals(10, result.rowCount)
    }

    @Test
    fun `F-003 상한보다 큰 LIMIT - 결과가 상한을 넘으면 행 수 상한 초과다`() {
        failed(ExecutionErrorKind.ROW_LIMIT_EXCEEDED, "SELECT n FROM numbers LIMIT 100000")
    }

    @Test
    fun `F-003 상한을 설정으로 바꿈 - 상한 50에 80행이면 오류의 상한 값은 50이다`() {
        val error =
            failed(
                ExecutionErrorKind.ROW_LIMIT_EXCEEDED,
                "SELECT n FROM numbers WHERE n <= 80",
                TestMySql.config(maxRows = 50),
            )

        assertContains(error.cause, "50")
        assertFalse(error.cause.contains("1000"), "원인: ${error.cause}")
    }

    @Test
    fun `F-003 상한을 설정으로 바꿈 - 결과가 상한과 같으면 오류가 아니다`() {
        val result = succeeded("SELECT n FROM numbers WHERE n <= 50", TestMySql.config(maxRows = 50))

        assertEquals(50, result.rowCount)
    }

    // 실행 결과 형식

    @Test
    fun `F-003 성공한 실행 - 열 이름, 행, 행 수, 걸린 시간, 실행한 SQL이 있다`() {
        val result =
            succeeded(
                "SELECT order_id, order_status AS status FROM orders WHERE customer_id IN ('c1', 'c2') " +
                    "AND order_status = 'delivered' ORDER BY order_id",
            )

        assertEquals(listOf("order_id", "status"), result.columns.map { it.name })
        assertEquals(
            listOf(listOf<Any?>("o1", "delivered"), listOf<Any?>("o2", "delivered"), listOf<Any?>("o6", "delivered")),
            result.rows,
        )
        assertEquals(3, result.rowCount)
        assertFalse(result.elapsed.isNegative || result.elapsed.isZero, "걸린 시간: ${result.elapsed}")
        assertContains(result.executedSql, "LIMIT 1001")
    }

    // 실행 오류 형식

    @Test
    fun `F-003 없는 컬럼 - DB 오류이고 원인에 DB가 알려 준 내용이 있다`() {
        val error = failed(ExecutionErrorKind.DB_ERROR, "SELECT no_such_column FROM orders")

        assertContains(error.cause, "no_such_column")
        assertContains(error.cause, "Unknown column", ignoreCase = true)
    }

    @Test
    fun `F-003 연결이 끊긴 상태 - 연결 실패이고 확인할 곳에 연결 상태 확인이 있다`() {
        // 아무것도 듣지 않는 포트. 꺼진 DB와 같다
        val config = TestMySql.config().copy(url = "jdbc:mysql://127.0.0.1:1/${TestMySql.SCHEMA}")

        val error = failed(ExecutionErrorKind.CONNECTION_FAILED, "SELECT COUNT(*) FROM orders", config)

        assertTrue(error.checkPoints.any { it.contains("연결 상태") }, "확인할 곳: ${error.checkPoints}")
        assertFalse(error.toString().contains(TestMySql.READER_PASSWORD), "오류에 비밀번호가 있다: $error")
    }

    // 개인정보 컬럼 값은 LLM에 보내지 않음

    @Test
    fun `F-003 SELECT 별표 - 그 테이블의 개인정보 컬럼 열은 LLM에 보낼 결과에서 빠진다`() {
        val result = succeeded("SELECT * FROM customers ORDER BY customer_id")

        assertEquals(
            mapOf(
                "customer_id" to false,
                "customer_unique_id" to true,
                "customer_zip_code_prefix" to true,
                "customer_state" to false,
            ),
            result.columns.associate { it.name to it.pii },
        )
        val safe = result.forLlm()
        assertEquals(listOf("customer_id", "customer_state"), safe.columns)
        assertEquals(listOf("customer_unique_id", "customer_zip_code_prefix"), safe.removedColumns)
        assertEquals(listOf<Any?>("c1", "SP"), safe.rows.first())
    }

    @Test
    fun `F-003 SELECT 별표 - 조인에서 테이블 별표로 들어온 개인정보 열도 빠진다`() {
        val result =
            succeeded(
                "SELECT o.order_id, c.* FROM orders o JOIN customers c ON o.customer_id = c.customer_id ORDER BY o.order_id",
            )

        val safe = result.forLlm()
        assertEquals(listOf("order_id", "customer_id", "customer_state"), safe.columns)
        assertEquals(listOf("customer_unique_id", "customer_zip_code_prefix"), safe.removedColumns)
    }

    @Test
    fun `F-003 화면용 결과는 그대로 - 실행 결과에는 개인정보 열이 표시된 채 남고 LLM에 보낼 결과에서만 빠진다`() {
        val result =
            succeeded("SELECT customer_zip_code_prefix AS zip, customer_state FROM customers ORDER BY customer_id")

        assertEquals(listOf("zip" to true, "customer_state" to false), result.columns.map { it.name to it.pii })
        assertEquals(listOf<Any?>("01001", "SP"), result.rows.first())

        val safe = result.forLlm()
        assertEquals(listOf("customer_state"), safe.columns)
        assertEquals(listOf("zip"), safe.removedColumns)
        assertEquals(listOf("zip", "customer_state"), result.columns.map { it.name }, "forLlm()이 실행 결과를 바꾸면 안 된다")
    }

    // 검사를 거친 SQL만 실행: 다시 쓴 SQL과 원문의 결과가 같다 (design 3장, Risks)

    private fun assertSameAsOriginal(sql: String) {
        val result = succeeded(sql)

        assertEquals(TestMySql.queryAsRoot(sql), result.rows, "원문: $sql\n실행한 SQL: ${result.executedSql}")
    }

    @Test
    fun `F-003 다시 쓴 SQL - CTE는 원문과 결과가 같다`() {
        assertSameAsOriginal(
            "WITH monthly AS (" +
                "SELECT DATE_FORMAT(order_purchase_timestamp, '%Y-%m') AS ym, COUNT(*) AS cnt, SUM(amount) AS total " +
                "FROM orders WHERE order_status <> 'canceled' GROUP BY ym" +
                ") SELECT ym, cnt, total FROM monthly ORDER BY ym",
        )
    }

    @Test
    fun `F-003 다시 쓴 SQL - UNION은 원문과 결과가 같다`() {
        assertSameAsOriginal(
            "SELECT customer_id FROM orders UNION SELECT customer_id FROM customers ORDER BY customer_id",
        )
        assertSameAsOriginal(
            "SELECT customer_id FROM orders UNION ALL SELECT customer_id FROM customers ORDER BY customer_id LIMIT 3",
        )
    }

    @Test
    fun `F-003 다시 쓴 SQL - 윈도 함수는 원문과 결과가 같다`() {
        assertSameAsOriginal(
            "SELECT order_id, " +
                "SUM(amount) OVER (PARTITION BY customer_id ORDER BY order_purchase_timestamp) AS running, " +
                "ROW_NUMBER() OVER (ORDER BY amount DESC) AS rn, " +
                "LAG(order_status) OVER w AS prev_status " +
                "FROM orders WINDOW w AS (ORDER BY order_purchase_timestamp) ORDER BY order_id",
        )
    }

    @Test
    fun `F-003 다시 쓴 SQL - 날짜 함수는 원문과 결과가 같다`() {
        assertSameAsOriginal(
            "SELECT order_id, DATE(order_purchase_timestamp) AS d, YEAR(order_purchase_timestamp) AS y, " +
                "DATE_ADD(order_purchase_timestamp, INTERVAL 1 MONTH) AS next_month, " +
                "TIMESTAMPDIFF(DAY, order_purchase_timestamp, '2018-12-31') AS days_left, " +
                "EXTRACT(QUARTER FROM order_purchase_timestamp) AS q " +
                "FROM orders WHERE order_purchase_timestamp >= '2018-02-01' - INTERVAL 7 DAY ORDER BY order_id",
        )
    }
}
