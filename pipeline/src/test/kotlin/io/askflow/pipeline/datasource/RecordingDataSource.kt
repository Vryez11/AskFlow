package io.askflow.pipeline.datasource

import java.io.PrintWriter
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * DB에 보낸 문장을 기록하는 가짜 JDBC. 실제 DB 없이 "DB에 닿지 않음"을 확인한다.
 * 문장을 받는 메서드(prepareStatement, execute 등)에 넘어온 SQL을 [sentSql]에 쌓는다.
 */
class RecordingDataSource : DataSource {
    val sentSql = mutableListOf<String>()
    var connectionCount = 0
        private set

    override fun getConnection(): Connection {
        connectionCount++
        return proxy(Connection::class.java)
    }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = getConnection()

    private fun <T> proxy(type: Class<T>): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                handle(self, method, args ?: emptyArray())
            },
        )

    private fun handle(
        self: Any,
        method: Method,
        args: Array<out Any?>,
    ): Any? {
        when (method.name) {
            "toString" -> return "RecordingDataSource proxy"
            "hashCode" -> return System.identityHashCode(self)
            "equals" -> return self === args.firstOrNull()
        }
        val sql = args.firstOrNull() as? String
        if (sql != null && method.name in SQL_METHODS) sentSql += sql
        return when (method.returnType) {
            Statement::class.java -> proxy(Statement::class.java)
            PreparedStatement::class.java -> proxy(PreparedStatement::class.java)
            CallableStatement::class.java -> proxy(CallableStatement::class.java)
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T> unwrap(iface: Class<T>): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>): Boolean = false

    private companion object {
        val SQL_METHODS =
            setOf(
                "prepareStatement",
                "prepareCall",
                "nativeSQL",
                "execute",
                "executeQuery",
                "executeUpdate",
                "executeLargeUpdate",
                "addBatch",
            )
    }
}
