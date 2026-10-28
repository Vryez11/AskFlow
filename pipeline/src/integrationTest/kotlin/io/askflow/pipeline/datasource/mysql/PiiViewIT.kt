package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionState
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.QueryResult
import io.askflow.pipeline.datasource.TargetDbConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 뷰와 생성 컬럼이 개인정보 컬럼을 다른 이름으로 내보내도 LLM에 보낼 결과에서 빠진다 (F-003, 7.1 재검토 막음).
 * 공용 스키마의 테이블 수를 세는 테스트가 있어 따로 스키마를 만든다.
 */
class PiiViewIT {
    @Test
    fun `F-003 뷰를 거친 열 - 개인정보 컬럼을 다른 이름으로 내보낸 뷰 열이 LLM에 보낼 결과에서 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT customer_id, region_code FROM customer_regions ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "01001"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("region_code"), safe.removedColumns)
    }

    @Test
    fun `F-003 뷰를 거친 열 - 뷰를 별표로 골라도 그 열이 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT * FROM customer_regions"))

        assertEquals(listOf("region_code"), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 개인정보 컬럼으로 만든 생성 컬럼 - 개인정보 컬럼을 잘라 계산한 생성 컬럼이 빠진다`() {
        val result = success(MySqlConnector(config()).execute("SELECT customer_id, zip_area FROM customers ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1", "010"), result.rows.first())
        val safe = result.forLlm()
        assertEquals(listOf("customer_id"), safe.columns)
        assertEquals(listOf("zip_area"), safe.removedColumns)
    }

    @Test
    fun `F-003 정의를 읽을 수 없는 뷰 - 정의를 볼 권한이 없으면 그 뷰에서 온 열이 모두 빠진다`() {
        val connector = MySqlConnector(config(username = NO_VIEW_USER, password = NO_VIEW_PASSWORD))

        val result = success(connector.execute("SELECT customer_id FROM customer_regions ORDER BY customer_id"))

        assertEquals(listOf<Any?>("c1"), result.rows.first())
        assertEquals(emptyList(), result.forLlm().columns)
        assertEquals(listOf("customer_id"), result.forLlm().removedColumns)
    }

    @Test
    fun `F-003 뷰를 거친 열 - 연결 확인 때 바뀐 뷰 정의를 다시 읽는다`() {
        TestMySql.root { connection ->
            connection.createStatement().use {
                it.execute("CREATE OR REPLACE VIEW $SCHEMA.late_view AS SELECT customer_state AS v FROM $SCHEMA.customers")
            }
        }
        val connector = MySqlConnector(config(allowedTables = listOf("customers", "late_view")))
        assertEquals(listOf("v"), success(connector.execute("SELECT v FROM late_view")).forLlm().columns)

        TestMySql.root { connection ->
            connection.createStatement().use {
                it.execute("CREATE OR REPLACE VIEW $SCHEMA.late_view AS SELECT customer_zip_code_prefix AS v FROM $SCHEMA.customers")
            }
        }
        assertEquals(ConnectionState.CONNECTED, connector.checkConnection().state)

        assertEquals(listOf("v"), success(connector.execute("SELECT v FROM late_view")).forLlm().removedColumns)
    }

    private fun success(outcome: ExecutionOutcome): QueryResult {
        assertIs<ExecutionOutcome.Success>(outcome, "결과: $outcome")
        return outcome.result
    }

    private fun config(
        username: String = TestMySql.READER,
        password: String = TestMySql.READER_PASSWORD,
        allowedTables: List<String> = listOf("customers", "customer_regions"),
    ): TargetDbConfig {
        prepared
        return TargetDbConfig(
            url = TestMySql.jdbcUrlOf(SCHEMA),
            schema = SCHEMA,
            username = username,
            password = password,
            allowedTables = allowedTables,
            // 뷰와 생성 컬럼은 적지 않고 원래 테이블의 컬럼만 적는다
            piiColumns = listOf("customers.customer_zip_code_prefix"),
        )
    }

    private companion object {
        const val SCHEMA = "pii_views"
        const val NO_VIEW_USER = "askflow_noview"
        const val NO_VIEW_PASSWORD = "noview-pw-7731"

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
                            zip_area VARCHAR(3) GENERATED ALWAYS AS (LEFT(customer_zip_code_prefix, 3)) VIRTUAL
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
                        "GRANT SELECT, SHOW VIEW ON $SCHEMA.* TO '${TestMySql.READER}'@'%'",
                        // SHOW VIEW가 없으면 VIEW_DEFINITION이 빈 문자열로 보인다
                        "CREATE USER '$NO_VIEW_USER'@'%' IDENTIFIED BY '$NO_VIEW_PASSWORD'",
                        "GRANT SELECT ON $SCHEMA.* TO '$NO_VIEW_USER'@'%'",
                    ).forEach { statement.execute(it.trimIndent()) }
                }
            }
        }
    }
}
