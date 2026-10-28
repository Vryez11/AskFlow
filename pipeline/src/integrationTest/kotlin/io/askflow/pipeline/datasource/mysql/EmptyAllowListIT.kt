package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 허용 테이블을 비우면 연결 확인에서 읽은 스키마의 테이블·뷰로 검사한다 (F-003, design 6장) */
class EmptyAllowListIT {
    private fun connector() = MySqlConnector(TestMySql.config(allowedTables = emptyList()))

    @Test
    fun `F-003 허용 테이블 비움 - 연결 확인 전에는 DB에 보내지 않고 연결 실패를 돌려준다`() {
        val outcome = connector().execute("SELECT COUNT(*) FROM orders")

        assertIs<ExecutionOutcome.Failure>(outcome)
        assertEquals(ExecutionErrorKind.CONNECTION_FAILED, outcome.error.kind)
    }

    @Test
    fun `F-003 허용 테이블 비움 - 연결 확인 뒤에는 스키마의 테이블과 뷰를 읽고 다른 스키마는 거부한다`() {
        val connector = connector()
        connector.checkConnection()

        for (sql in listOf("SELECT COUNT(*) FROM orders", "SELECT COUNT(*) FROM delivered_orders")) {
            assertIs<ExecutionOutcome.Success>(connector.execute(sql), "SQL: $sql")
        }
        val rejected = connector.execute("SELECT * FROM ${TestMySql.FORBIDDEN_SCHEMA}.salaries")
        assertIs<ExecutionOutcome.Failure>(rejected)
        assertEquals(ExecutionErrorKind.TABLE_NOT_ALLOWED, rejected.error.kind)
    }
}
