package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 대소문자만 다른 스키마, 세션에 값을 남기는 사용자 변수와 LAST_INSERT_ID 거부 (F-003, 7.1 4차 검토) */
class SqlGuardSessionTest {
    private val guard = SqlGuard(schema = "olist", allowedTables = setOf("orders", "customers"), maxRows = 1000)

    private fun assertRejectedAs(
        kind: ExecutionErrorKind,
        sql: String,
    ): ExecutionError {
        val result = guard.check(sql)
        assertIs<GuardResult.Rejected>(result, "거부해야 하는 SQL: $sql")
        assertEquals(kind, result.error.kind, "SQL: $sql")
        return result.error
    }

    @Test
    fun `F-003 대소문자만 다른 스키마 - 허용하지 않은 테이블로 거부된다`() {
        assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, "SELECT COUNT(*) FROM OLIST.orders")
        assertRejectedAs(ExecutionErrorKind.TABLE_NOT_ALLOWED, "SELECT COUNT(*) FROM `Olist`.`orders`")
    }

    @Test
    fun `F-003 사용자 변수 - 읽기와 대입 모두 허용하지 않은 함수로 거부되고 원인에 사용자 변수가 있다`() {
        listOf(
            "SELECT @zip",
            "SELECT @zip := customer_zip_code_prefix FROM customers",
            "SELECT order_id FROM orders WHERE customer_id = @cid",
        ).forEach { sql ->
            assertContains(assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, sql).cause, "사용자 변수")
        }
    }

    @Test
    fun `F-003 LAST_INSERT_ID - 허용하지 않은 함수로 거부된다`() {
        assertContains(
            assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT LAST_INSERT_ID()").cause,
            "LAST_INSERT_ID",
            ignoreCase = true,
        )
        assertRejectedAs(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "SELECT last_insert_id(5)")
    }
}
