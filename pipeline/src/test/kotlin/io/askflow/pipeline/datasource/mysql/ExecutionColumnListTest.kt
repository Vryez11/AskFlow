package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.TargetDbConfig
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실행 경로는 실행 때 읽은 컬럼 목록을 판정기에 반드시 넘긴다 (F-003, design 7장 "출처 이름") */
class ExecutionColumnListTest {
    @Test
    fun `F-003 확인하지 못한 이름 - 실행 때 읽은 컬럼 목록에 없는 열은 LLM에 보낼 결과에서 빠진다`() {
        // 목록에는 orders.order_id만 있고, 결과의 orders.note는 목록에 없다
        val dataSource =
            FakeDataSource(
                schemaRows = listOf(listOf("COLUMN", "orders", "order_id")),
                resultColumns = listOf("orders" to "order_id", "orders" to "note"),
                resultRow = listOf(1, "메모"),
            )
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

        val outcome = connector.execute("SELECT order_id, note FROM orders")

        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        assertEquals(listOf("order_id"), outcome.result.forLlm().columns)
        assertEquals(listOf("note"), outcome.result.forLlm().removedColumns)
    }

    /** 목록 조회(`prepareStatement`)에는 [schemaRows]를, 사용자 SQL(`createStatement`)에는 한 행짜리 결과를 돌려주는 가짜 JDBC */
    private class FakeDataSource(
        private val schemaRows: List<List<Any?>>,
        /** (테이블, 컬럼). 열 이름(label)은 컬럼과 같다 */
        private val resultColumns: List<Pair<String, String>>,
        private val resultRow: List<Any?>,
    ) : DataSource {
        override fun getConnection(): Connection =
            proxy(Connection::class.java) { name, _ ->
                when (name) {
                    "prepareStatement" -> preparedStatement()
                    "createStatement" -> statement()
                    else -> null
                }
            }

        private fun preparedStatement(): PreparedStatement =
            proxy(PreparedStatement::class.java) { name, _ ->
                if (name == "executeQuery") resultSet(schemaRows, metaData = null) else null
            }

        private fun statement(): Statement =
            proxy(Statement::class.java) { name, _ ->
                if (name == "executeQuery") resultSet(listOf(resultRow), metaData()) else null
            }

        private fun metaData(): ResultSetMetaData =
            proxy(ResultSetMetaData::class.java) { name, args ->
                val column = (args.firstOrNull() as? Int)?.let { resultColumns[it - 1] }
                when (name) {
                    "getColumnCount" -> resultColumns.size
                    "getTableName" -> column?.first
                    "getColumnName", "getColumnLabel" -> column?.second
                    else -> null
                }
            }

        private fun resultSet(
            rows: List<List<Any?>>,
            metaData: ResultSetMetaData?,
        ): ResultSet {
            var index = -1
            return proxy(ResultSet::class.java) { name, args ->
                when (name) {
                    "next" -> ++index < rows.size
                    "getMetaData" -> metaData
                    "getString" -> rows[index][(args.first() as Int) - 1]?.toString()
                    "getObject" -> rows[index][(args.first() as Int) - 1]
                    else -> null
                }
            }
        }

        private fun <T> proxy(
            type: Class<T>,
            handler: (String, Array<out Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                    when (method.name) {
                        "toString" -> "FakeDataSource proxy"
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
