package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.QueryResult
import io.askflow.pipeline.datasource.TargetDbConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 뷰에서 온 열, 생성 컬럼, 저장 함수를 부른 열은 정의와 상관없이 LLM에 보낼 결과에서 빠진다 (F-003, 7.1 재검토 막음).
 * 공용 스키마의 테이블 수를 세는 테스트가 있어 따로 스키마를 만든다.
 */
class PiiViewIT {
    @Test
    fun `F-003 뷰에서 온 열 - 개인정보가 아닌 열을 포함해 뷰의 모든 열이 LLM에 보낼 결과에서 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT customer_id, region_code FROM customer_regions ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "01001"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(emptyList(), safe.columns)
        assertEquals(listOf("customer_id", "region_code"), safe.removedColumns)
    }

    @Test
    fun `F-003 뷰에서 온 열 - 뷰를 별표로 골라도 모든 열이 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT * FROM customer_regions"))

        assertEquals(listOf("customer_id", "region_code"), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 뷰에서 온 열 - 정의를 볼 권한이 없어도 뷰의 열이 모두 빠진다`() {
        val connector = MySqlConnector(config(username = NO_VIEW_USER, password = NO_VIEW_PASSWORD))

        val result = success(connector.execute("SELECT customer_id FROM customer_regions ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1"), result.rows.first())
        assertEquals(emptyList(), result.forLlm().columns)
        assertEquals(listOf("customer_id"), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 뷰의 행 수 - 뷰에서 COUNT만 고르면 LLM에 보낼 결과에 남는다`() {
        val result = success(MySqlConnector(config()).execute("SELECT COUNT(*) AS n FROM customer_regions"))

        assertEquals(listOf("n"), result.forLlm().columns)
        assertEquals(emptyList(), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 생성 컬럼 - 개인정보 컬럼을 잘라 계산한 생성 컬럼이 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT customer_id, zip_area FROM customers ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "010"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("zip_area"), safe.removedColumns)
    }

    @Test
    fun `F-003 생성 컬럼 - 개인정보 컬럼으로 계산하지 않는 생성 컬럼도 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT customer_id, state_lower FROM customers ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "sp"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("state_lower"), safe.removedColumns)
    }

    @Test
    fun `F-003 실행 사이에 바뀐 스키마 - 연결 확인 없이 새로 생긴 생성 컬럼과 뷰의 열이 빠진다`() {
        root(
            "CREATE TABLE $SCHEMA.late_customers (customer_id VARCHAR(32) PRIMARY KEY, customer_zip_code_prefix VARCHAR(5) NOT NULL)",
            "INSERT INTO $SCHEMA.late_customers VALUES ('c1', '01001')",
        )
        val connector =
            MySqlConnector(
                config(
                    allowedTables = listOf("late_customers", "late_view"),
                    piiColumns = listOf("late_customers.customer_zip_code_prefix"),
                ),
            )
        assertEquals(listOf("customer_id"), success(connector.execute("SELECT customer_id FROM late_customers")).forLlm().columns)

        root(
            "ALTER TABLE $SCHEMA.late_customers ADD COLUMN zip_copy VARCHAR(5) GENERATED ALWAYS AS (customer_zip_code_prefix) VIRTUAL",
            "CREATE VIEW $SCHEMA.late_view AS SELECT customer_zip_code_prefix AS v FROM $SCHEMA.late_customers",
        )

        assertEquals(listOf("zip_copy"), success(connector.execute("SELECT zip_copy FROM late_customers")).forLlm().removedColumns)
        assertEquals(listOf("v"), success(connector.execute("SELECT v FROM late_view")).forLlm().removedColumns)
    }

    @Test
    fun `F-003 저장 함수 - 설정한 스키마의 저장 함수를 부른 열이 빠진다`() {
        val connector = MySqlConnector(config(username = EXEC_USER, password = EXEC_PASSWORD))

        val result = success(connector.execute("SELECT customer_id, zip_of(customer_id) AS z FROM customers ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "01001"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("z"), safe.removedColumns)
    }

    private fun success(outcome: ExecutionOutcome): QueryResult {
        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        return outcome.result
    }

    private fun root(vararg sql: String) {
        TestMySql.root { connection ->
            connection.createStatement().use { statement -> sql.forEach(statement::execute) }
        }
    }

    private fun config(
        username: String = TestMySql.READER,
        password: String = TestMySql.READER_PASSWORD,
        allowedTables: List<String> = listOf("customers", "customer_regions"),
        // 뷰와 생성 컬럼은 적지 않고 원래 테이블의 컬럼만 적는다
        piiColumns: List<String> = listOf("customers.customer_zip_code_prefix"),
    ): TargetDbConfig {
        prepared
        return TargetDbConfig(
            url = TestMySql.jdbcUrlOf(SCHEMA),
            schema = SCHEMA,
            username = username,
            password = password,
            allowedTables = allowedTables,
            piiColumns = piiColumns,
        )
    }

    private companion object {
        const val SCHEMA = "pii_views"
        const val NO_VIEW_USER = "askflow_noview"
        val NO_VIEW_PASSWORD = TestMySql.testPassword()
        const val EXEC_USER = "askflow_exec"
        val EXEC_PASSWORD = TestMySql.testPassword()

        val prepared: Unit by lazy {
            TestMySql.root { connection ->
                connection.createStatement().use { statement ->
                    listOf(
                        "CREATE DATABASE $SCHEMA",
                        """
                        CREATE TABLE $SCHEMA.customers (
                            customer_id VARCHAR(32) PRIMARY KEY,
                            customer_zip_code_prefix VARCHAR(5) NOT NULL,
                            customer_state CHAR(2) NOT NULL,
                            zip_area VARCHAR(3) GENERATED ALWAYS AS (LEFT(customer_zip_code_prefix, 3)) VIRTUAL,
                            state_lower CHAR(2) GENERATED ALWAYS AS (LOWER(customer_state)) VIRTUAL
                        )
                        """,
                        """
                        INSERT INTO $SCHEMA.customers (customer_id, customer_zip_code_prefix, customer_state) VALUES
                            ('c1', '01001', 'SP'),
                            ('c2', '20000', 'RJ')
                        """,
                        """
                        CREATE VIEW $SCHEMA.customer_regions AS
                            SELECT customer_id, customer_zip_code_prefix AS region_code FROM $SCHEMA.customers
                        """,
                        // 인자로 받은 고객의 우편번호를 돌려준다. 인자만 보면 개인정보 컬럼이 보이지 않는다
                        """
                        CREATE FUNCTION $SCHEMA.zip_of(id VARCHAR(32)) RETURNS VARCHAR(5) READS SQL DATA
                            RETURN (SELECT customer_zip_code_prefix FROM $SCHEMA.customers WHERE customer_id = id)
                        """,
                        "GRANT SELECT, SHOW VIEW ON $SCHEMA.* TO '${TestMySql.READER}'@'%'",
                        // SHOW VIEW가 없으면 VIEW_DEFINITION이 빈 문자열로 보인다
                        "CREATE USER '$NO_VIEW_USER'@'%' IDENTIFIED BY '$NO_VIEW_PASSWORD'",
                        "GRANT SELECT ON $SCHEMA.* TO '$NO_VIEW_USER'@'%'",
                        // 저장 함수는 EXECUTE가 있어야 부를 수 있다. 공용 reader 계정의 권한은 건드리지 않는다
                        "CREATE USER '$EXEC_USER'@'%' IDENTIFIED BY '$EXEC_PASSWORD'",
                        "GRANT SELECT, SHOW VIEW, EXECUTE ON $SCHEMA.* TO '$EXEC_USER'@'%'",
                    ).forEach { statement.execute(it.trimIndent()) }
                }
            }
        }
    }
}
