package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 사용자 변수, LAST_INSERT_ID, 대소문자만 다른 스키마는 CTE 본문과 서브쿼리 안에서도 거부된다 (F-003, 7.1 5차 검토) */
class SqlGuardNestedSessionTest {
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
    fun `F-003 사용자 변수 - CTE 본문과 서브쿼리 안에서도 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "WITH t AS (SELECT @zip AS z) SELECT z FROM t")
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT order_id FROM orders WHERE order_id IN (SELECT @cid)")
    }

    @Test
    fun `F-003 LAST_INSERT_ID - CTE 본문과 서브쿼리 안에서도 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "WITH t AS (SELECT LAST_INSERT_ID() AS i) SELECT i FROM t")
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT (SELECT LAST_INSERT_ID()) FROM orders")
    }

    @Test
    fun `F-003 대소문자만 다른 스키마 - CTE 본문과 서브쿼리 안에서도 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, "WITH t AS (SELECT order_id FROM OLIST.orders) SELECT order_id FROM t")
        assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, "SELECT order_id FROM orders WHERE EXISTS (SELECT 1 FROM Olist.customers)")
    }
}
