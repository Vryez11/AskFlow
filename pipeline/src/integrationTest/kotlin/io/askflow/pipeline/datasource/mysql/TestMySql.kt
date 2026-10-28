package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.TargetDbConfig
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

/**
 * 통합 테스트가 함께 쓰는 MySQL 8.4 (design 9장). 처음 쓸 때 한 번 띄우고 테스트용 스키마와 계정을 만든다.
 * Olist 대신 작은 스키마를 쓴다: orders, customers(개인정보 컬럼), numbers(1..5000), 뷰 delivered_orders.
 */
object TestMySql {
    const val SCHEMA = "olist"
    const val READER = "askflow_reader"
    const val READER_PASSWORD = "reader-pw-7731"
    const val WRITER = "askflow_writer"
    const val WRITER_PASSWORD = "writer-pw-7731"
    const val ADMIN = "askflow_admin"
    const val ADMIN_PASSWORD = "admin-pw-7731"
    const val NUMBERS = 5000

    /** reader가 접근할 수 없는 다른 스키마 */
    const val FORBIDDEN_SCHEMA = "hr"

    val tables = listOf("orders", "customers", "numbers")

    /** 허용 테이블을 비우면 테이블과 함께 허용되는 뷰 */
    val views = listOf("delivered_orders")
    val piiColumns = listOf("customers.customer_zip_code_prefix", "customers.customer_unique_id")

    private val container: MySQLContainer by lazy {
        MySQLContainer("mysql:8.4")
            .withDatabaseName(SCHEMA)
            .also { it.start() }
            .also { createSchema(it) }
    }

    val jdbcUrl: String get() = urlOf(container)

    val host: String get() = container.host

    val port: Int get() = container.getMappedPort(3306)

    private fun urlOf(
        container: MySQLContainer,
        schema: String = SCHEMA,
    ) = "jdbc:mysql://${container.host}:${container.getMappedPort(3306)}/$schema"

    /** 같은 DB의 다른 스키마를 가리키는 접속 주소 */
    fun jdbcUrlOf(schema: String): String = urlOf(container, schema)

    fun config(
        username: String = READER,
        password: String = READER_PASSWORD,
        allowedTables: List<String> = tables,
        maxRows: Int = TargetDbConfig.DEFAULT_MAX_ROWS,
        queryTimeout: Duration = TargetDbConfig.DEFAULT_QUERY_TIMEOUT,
    ): TargetDbConfig =
        TargetDbConfig(
            url = jdbcUrl,
            schema = SCHEMA,
            username = username,
            password = password,
            allowedTables = allowedTables,
            piiColumns = piiColumns,
            maxRows = maxRows,
            queryTimeout = queryTimeout,
        )

    /** 검사와 커넥터를 거치지 않는 root 연결. 준비와 비교용으로만 쓴다 */
    fun <T> root(block: (Connection) -> T): T = DriverManager.getConnection(jdbcUrl, "root", container.password).use(block)

    /** root로 SQL을 그대로 실행한 결과. 다시 쓴 SQL과 원문의 결과를 비교할 때 쓴다 */
    fun queryAsRoot(sql: String): List<List<Any?>> =
        root { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val count = rs.metaData.columnCount
                    buildList {
                        while (rs.next()) add((1..count).map { rs.getObject(it) })
                    }
                }
            }
        }

    private fun createSchema(container: MySQLContainer) {
        // lazy 초기화 중이라 jdbcUrl(=container)을 다시 부르지 않는다
        DriverManager.getConnection(urlOf(container), "root", container.password).use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    """
                    CREATE TABLE customers (
                        customer_id VARCHAR(32) PRIMARY KEY,
                        customer_unique_id VARCHAR(32) NOT NULL,
                        customer_zip_code_prefix VARCHAR(5) NOT NULL,
                        customer_state CHAR(2) NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE orders (
                        order_id VARCHAR(32) PRIMARY KEY,
                        customer_id VARCHAR(32) NOT NULL,
                        order_status VARCHAR(16) NOT NULL,
                        order_purchase_timestamp DATETIME NOT NULL,
                        amount DECIMAL(10, 2) NOT NULL
                    )
                    """,
                    "CREATE TABLE numbers (n INT PRIMARY KEY)",
                    """
                    INSERT INTO customers VALUES
                        ('c1', 'u1', '01001', 'SP'),
                        ('c2', 'u2', '20000', 'RJ'),
                        ('c3', 'u3', '30100', 'MG'),
                        ('c4', 'u1', '01002', 'SP')
                    """,
                    """
                    INSERT INTO orders VALUES
                        ('o1', 'c1', 'delivered', '2018-01-05 10:00:00', 120.50),
                        ('o2', 'c1', 'delivered', '2018-01-20 15:30:00', 80.00),
                        ('o3', 'c2', 'canceled', '2018-02-11 09:10:00', 45.90),
                        ('o4', 'c3', 'delivered', '2018-02-28 23:59:59', 300.00),
                        ('o5', 'c4', 'shipped', '2018-03-01 00:00:00', 15.25),
                        ('o6', 'c2', 'delivered', '2018-03-15 12:00:00', 99.99)
                    """,
                    "SET SESSION cte_max_recursion_depth = $NUMBERS",
                    """
                    INSERT INTO numbers (n)
                    WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < $NUMBERS)
                    SELECT n FROM seq
                    """,
                    "CREATE USER '$READER'@'%' IDENTIFIED BY '$READER_PASSWORD'",
                    "GRANT SELECT, SHOW VIEW ON $SCHEMA.* TO '$READER'@'%'",
                    // 계정이 쓰기 권한을 가져도 세션이 막는지 볼 때 쓴다
                    "CREATE USER '$WRITER'@'%' IDENTIFIED BY '$WRITER_PASSWORD'",
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON $SCHEMA.* TO '$WRITER'@'%'",
                    // 전체 권한 경고를 볼 때 쓴다
                    "CREATE USER '$ADMIN'@'%' IDENTIFIED BY '$ADMIN_PASSWORD'",
                    "GRANT ALL PRIVILEGES ON *.* TO '$ADMIN'@'%'",
                    "CREATE VIEW delivered_orders AS SELECT order_id, amount FROM orders WHERE order_status = 'delivered'",
                    "CREATE DATABASE $FORBIDDEN_SCHEMA",
                ).forEach { statement.execute(it.trimIndent()) }
            }
        }
    }
}
