package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 테이블·함수 검사를 피해 갈 만한 자리 (F-003, design 3장) */
class SqlGuardBypassTest {
    private val guard = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = 1000)

    private fun assertPassed(sql: String) {
        assertIs<GuardResult.Passed>(guard.check(sql), "통과해야 하는 SQL: $sql")
    }

    private fun assertRejectedAs(
        kind: ExecutionErrorKind,
        sql: String,
    ) {
        val result = guard.check(sql)
        assertIs<GuardResult.Rejected>(result, "거부해야 하는 SQL: $sql")
        assertEquals(kind, result.error.kind, "SQL: $sql")
    }

    @Test
    fun `F-003 허용하지 않은 테이블 - 비교식, 정렬, 그룹, 함수 인자, CASE 안 서브쿼리도 거부된다`() {
        listOf(
            "SELECT order_id FROM orders WHERE order_id > (SELECT MAX(seller_id) FROM sellers)",
            "SELECT order_id FROM orders ORDER BY (SELECT COUNT(*) FROM sellers)",
            "SELECT order_id FROM orders GROUP BY order_id HAVING COUNT(*) < (SELECT COUNT(*) FROM sellers)",
            "SELECT COALESCE((SELECT seller_id FROM sellers LIMIT 1), 'x') FROM orders",
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sellers) THEN 1 ELSE 0 END FROM orders",
            "SELECT order_id FROM (SELECT * FROM sellers) AS s",
        ).forEach { assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, it) }
    }

    @Test
    fun `F-003 CTE 이름 - 범위 밖이나 자기 본문에서 같은 이름의 테이블을 읽으면 거부된다`() {
        listOf(
            // RECURSIVE가 아니면 CTE 본문의 sellers는 실제 테이블이다
            "WITH sellers AS (SELECT * FROM sellers) SELECT * FROM sellers",
            // 뒤에 정의한 CTE는 앞 CTE 본문에서 보이지 않는다
            "WITH a AS (SELECT * FROM sellers), sellers AS (SELECT order_id FROM orders) SELECT * FROM a",
            // 서브쿼리 안의 CTE 이름은 바깥에서 보이지 않는다
            "SELECT * FROM sellers WHERE 1 IN (WITH sellers AS (SELECT 1 AS x) SELECT x FROM sellers)",
            // 스키마를 적으면 CTE가 아니라 테이블이다
            "WITH sellers AS (SELECT order_id FROM orders) SELECT * FROM olist.sellers",
        ).forEach { assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, it) }
    }

    @Test
    fun `F-003 CTE 이름 - 앞 CTE와 재귀 CTE 자신은 테이블로 보지 않는다`() {
        assertPassed("WITH a AS (SELECT order_id FROM orders), b AS (SELECT * FROM a) SELECT * FROM b")
        assertPassed(
            "WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10) SELECT n FROM seq",
        )
    }

    @Test
    fun `F-003 허용 테이블만 씀 - 컬럼 앞의 테이블 별칭은 테이블로 보지 않는다`() {
        assertPassed("SELECT x.order_id, x.* FROM orders AS x")
    }

    @Test
    fun `F-003 다른 스키마 - 세 부분 이름은 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, "SELECT * FROM srv.olist.orders")
    }

    @Test
    fun `F-003 부작용이 있는 함수 - 정렬과 조인 조건 안에서 불러도 거부된다`() {
        listOf(
            "SELECT order_id FROM orders ORDER BY SLEEP(1)",
            "SELECT o.order_id FROM orders o JOIN customers c ON SLEEP(1) = 0",
            "SELECT order_id FROM orders WHERE order_id > BENCHMARK(1000, MD5('a'))",
        ).forEach { assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, it) }
    }
}
