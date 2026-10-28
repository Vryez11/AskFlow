package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** 보통 주석 안의 따옴표가 실행 주석·힌트 검사를 속이지 못한다 (F-003, 7.1 검토의 막음) */
class SqlGuardCommentTest {
    private val guard = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = 1000)

    private fun assertRejectedAs(
        kind: ExecutionErrorKind,
        sql: String,
    ) {
        val result = guard.check(sql)
        assertIs<GuardResult.Rejected>(result, "거부해야 하는 SQL: $sql")
        assertEquals(kind, result.error.kind, "SQL: $sql")
    }

    @Test
    fun `F-003 실행 시간 힌트 - 보통 주석 안 따옴표 뒤에 있어도 SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "-- it's\nSELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
            "# it's\nSELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
            "/* it's */ SELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
            "-- \"x\nSELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
            "-- `x\nSELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 실행 주석 - 보통 주석 안 따옴표 뒤에 있어도 SELECT가 아닌 SQL로 거부된다`() {
        listOf(
            "-- it's\nSELECT COUNT(*) /*!50000 , SLEEP(10) */ FROM orders",
            "/* it's */ SELECT COUNT(*) /*!50000 , SLEEP(10) */ FROM orders",
        ).forEach { assertRejectedAs(ExecutionErrorKind.NOT_SELECT, it) }
    }

    @Test
    fun `F-003 보통 주석 - 따옴표가 든 주석만 있으면 통과하고 DB에 보내는 문장에는 주석이 없다`() {
        val result = guard.check("-- it's\nSELECT COUNT(*) /* don't */ FROM orders")

        assertIs<GuardResult.Passed>(result)
        assertFalse(result.sql.contains("--"), "다시 쓴 SQL: ${result.sql}")
        assertFalse(result.sql.contains("/*"), "다시 쓴 SQL: ${result.sql}")
    }
}
