package io.askflow.pipeline.datasource.mysql

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 읽기 전용 세션이 풀에 반납된 뒤에도 남는지 (F-003, design 4장) */
class ReadOnlySessionIT {
    @Test
    fun `F-003 읽기 전용 세션 - JDBC readOnly를 켰다 끄고 반납한 연결도 DB가 쓰기를 거부한다`() {
        val pool =
            MySqlConnector.createPool(
                TestMySql.config(username = TestMySql.WRITER, password = TestMySql.WRITER_PASSWORD),
            )
        // 커넥터의 execute처럼 readOnly를 켰다가 반납한다. 풀은 반납 때 readOnly를 기본값으로 되돌린다
        repeat(MAX_POOL_SIZE) {
            pool.connection.use { connection ->
                connection.isReadOnly = true
                connection.rollback()
            }
        }

        repeat(MAX_POOL_SIZE) {
            val error =
                assertFailsWith<SQLException> {
                    pool.connection.use { connection ->
                        try {
                            connection.createStatement().use { it.executeUpdate("DELETE FROM orders") }
                        } finally {
                            connection.rollback()
                        }
                    }
                }
            assertEquals(1792, error.errorCode, "DB 오류: ${error.message}")
        }
    }

    private companion object {
        const val MAX_POOL_SIZE = 4
    }
}
