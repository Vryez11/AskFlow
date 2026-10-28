package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionErrorKind
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.TargetDbConfig
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실행마다 읽는 스키마 목록(뷰, 생성 컬럼, 저장 함수, 컬럼)을 읽지 못하면 사용자 SQL을 보내지 않는다 (F-003, design 7장) */
class SchemaListFailureTest {
    @Test
    fun `F-003 실행 사이에 바뀐 스키마 - 목록을 읽지 못하면 사용자 SQL을 보내지 않고 연결 실패를 돌려준다`() {
        val dataSource = SchemaListFailingDataSource()
        val connector =
            MySqlConnector(
                TargetDbConfig(
                    url = "jdbc:mysql://fake:3306/olist",
                    schema = "olist",
                    username = "askflow_reader",
                    password = "secret",
                    allowedTables = listOf("orders"),
                ),
                dataSource,
            )

        val outcome = connector.execute("SELECT order_id FROM orders")

        assertIs<ExecutionOutcome.Failure>(outcome, "결과: $outcome")
        assertEquals(ExecutionErrorKind.CONNECTION_FAILED, outcome.error.kind)
        assertTrue(dataSource.listQueries.isNotEmpty(), "목록 조회를 시도하지 않았다")
        assertTrue(dataSource.userSql.isEmpty(), "DB에 보낸 사용자 SQL: ${dataSource.userSql}")
    }

    /** 목록 조회(`prepareStatement`)는 실패시키고, 사용자 SQL(`createStatement` 뒤 `execute*`)은 기록하는 가짜 JDBC */
    private class SchemaListFailingDataSource : DataSource {
        val listQueries = mutableListOf<String>()
        val userSql = mutableListOf<String>()

        override fun getConnection(): Connection =
            proxy(Connection::class.java) { name, args ->
                when (name) {
                    "prepareStatement" -> {
                        listQueries += args.first() as String
                        throw SQLException("information_schema를 읽지 못함", "42000", 1142)
                    }
                    "createStatement" -> statement()
                    else -> null
                }
            }

        private fun statement(): Statement =
            proxy(Statement::class.java) { name, args ->
                if (name.startsWith("execute")) userSql += args.first() as String
                null
            }

        private fun <T> proxy(
            type: Class<T>,
            handler: (String, Array<out Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                    when (method.name) {
                        "toString" -> "SchemaListFailingDataSource proxy"
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.firstOrNull()
                        else -> handler(method.name, args ?: emptyArray()) ?: defaultOf(method.returnType)
                    }
                },
            )

        private fun defaultOf(type: Class<*>): Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connection

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getGlobal()

        override fun <T> unwrap(iface: Class<T>): T = throw UnsupportedOperationException()

        override fun isWrapperFor(iface: Class<*>): Boolean = false
    }
}
