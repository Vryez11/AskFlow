package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.QueryResult
import io.askflow.pipeline.datasource.ResultColumn
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** safe-sql-execution "개인정보 컬럼 값은 LLM에 보내지 않음"의 해석 트리 추적 (F-003) */
class PiiLineageTest {
    private val lineage =
        PiiLineage(
            piiColumns = listOf("customers.customer_zip_code_prefix", "Customers.Customer_Unique_Id"),
        )

    private fun assertTrace(
        expected: List<ItemPii>,
        sql: String,
    ) {
        assertEquals(expected, lineage.trace(sql), "SQL: $sql")
    }

    // 개인정보 컬럼을 그대로 고름

    @Test
    fun `F-003 개인정보 컬럼을 그대로 고름 - 개인정보 열과 아닌 열을 나눈다`() {
        assertTrace(
            listOf(ItemPii.PII, ItemPii.SAFE),
            "SELECT customer_zip_code_prefix, customer_state FROM customers",
        )
        assertTrace(
            listOf(ItemPii.PII, ItemPii.SAFE),
            "SELECT c.customer_zip_code_prefix, c.customer_state FROM customers c",
        )
        assertTrace(listOf(ItemPii.PII), "SELECT customers.customer_zip_code_prefix FROM olist.customers")
    }

    @Test
    fun `F-003 개인정보 컬럼을 그대로 고름 - 설정의 대소문자는 상관없다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT customer_unique_id FROM customers")
        assertTrace(listOf(ItemPii.PII), "SELECT CUSTOMER_ZIP_CODE_PREFIX FROM CUSTOMERS")
    }

    @Test
    fun `F-003 개인정보 컬럼을 그대로 고름 - 테이블 없이 쓴 컬럼은 조인한 테이블 중 하나라도 개인정보면 가린다`() {
        assertTrace(
            listOf(ItemPii.SAFE, ItemPii.PII),
            "SELECT o.order_id, customer_zip_code_prefix FROM orders o JOIN customers c ON o.customer_id = c.customer_id",
        )
    }

    @Test
    fun `F-003 개인정보 컬럼을 그대로 고름 - LLM에 보낼 결과에는 개인정보가 아닌 열만 남는다`() {
        val result =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("customer_zip_code_prefix", pii = true),
                        ResultColumn("customer_state", pii = false),
                    ),
                rows = listOf(listOf("01001", "SP"), listOf("20000", "RJ")),
                elapsed = Duration.ofMillis(12),
                executedSql = "SELECT customer_zip_code_prefix, customer_state FROM customers LIMIT 1001",
            )

        val safe = result.forLlm()

        assertEquals(listOf("customer_state"), safe.columns)
        assertEquals(listOf(listOf<Any?>("SP"), listOf<Any?>("RJ")), safe.rows)
        assertEquals(listOf("customer_zip_code_prefix"), safe.removedColumns)
    }

    @Test
    fun `F-003 화면용 결과는 그대로 - LLM에 보낼 결과를 만들어도 실행 결과의 열은 남는다`() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("customer_zip_code_prefix", pii = true)),
                rows = listOf(listOf("01001")),
                elapsed = Duration.ofMillis(5),
                executedSql = "SELECT customer_zip_code_prefix FROM customers LIMIT 1001",
            )

        result.forLlm()

        assertEquals(listOf(ResultColumn("customer_zip_code_prefix", pii = true)), result.columns)
        assertEquals(listOf(listOf<Any?>("01001")), result.rows)
    }

    // 별칭과 함수

    @Test
    fun `F-003 별칭과 함수 - 별칭을 붙이거나 함수로 바꾼 개인정보 열은 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT customer_zip_code_prefix AS zip FROM customers")
        assertTrace(listOf(ItemPii.PII), "SELECT LEFT(customer_zip_code_prefix, 2) FROM customers")
        assertTrace(listOf(ItemPii.PII), "SELECT CONCAT('zip-', c.customer_zip_code_prefix) AS z FROM customers c")
    }

    // SELECT *

    @Test
    fun `F-003 SELECT 별표 - 펼친 열은 메타데이터로 판정하도록 STAR로 둔다`() {
        assertTrace(listOf(ItemPii.STAR), "SELECT * FROM customers")
        assertTrace(
            listOf(ItemPii.STAR, ItemPii.SAFE),
            "SELECT c.*, o.order_id FROM orders o JOIN customers c ON o.customer_id = c.customer_id",
        )
    }

    // COUNT 안에서만 씀

    @Test
    fun `F-003 COUNT 안에서만 씀 - 개인정보 열로 보지 않는다`() {
        assertTrace(
            listOf(ItemPii.SAFE, ItemPii.SAFE),
            "SELECT customer_state, COUNT(DISTINCT customer_unique_id) FROM customers GROUP BY customer_state",
        )
        assertTrace(listOf(ItemPii.SAFE), "SELECT COUNT(customer_zip_code_prefix) AS n FROM customers")
        assertTrace(listOf(ItemPii.SAFE), "SELECT COUNT(*) FROM customers")
    }

    // 값을 돌려주는 집계

    @Test
    fun `F-003 값을 돌려주는 집계 - COUNT가 아닌 집계로 고른 개인정보 열은 가린다`() {
        listOf(
            "SELECT MAX(customer_zip_code_prefix) FROM customers",
            "SELECT MIN(customer_zip_code_prefix) FROM customers",
            "SELECT GROUP_CONCAT(customer_unique_id) FROM customers",
            "SELECT SUM(customer_zip_code_prefix) FROM customers",
        ).forEach { assertTrace(listOf(ItemPii.PII), it) }
    }

    // 서브쿼리를 거친 열

    @Test
    fun `F-003 서브쿼리를 거친 열 - 서브쿼리와 CTE를 거쳐도 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT z FROM (SELECT customer_zip_code_prefix AS z FROM customers) t")
        assertTrace(listOf(ItemPii.PII), "SELECT t.z FROM (SELECT customer_zip_code_prefix AS z FROM customers) t")
        assertTrace(
            listOf(ItemPii.PII),
            "WITH c AS (SELECT customer_zip_code_prefix AS z FROM customers) SELECT z FROM c",
        )
        assertTrace(
            listOf(ItemPii.SAFE),
            "WITH c AS (SELECT customer_state AS s FROM customers) SELECT s FROM c",
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - 서브쿼리 안에서 COUNT로 센 값은 남는다`() {
        assertTrace(
            listOf(ItemPii.SAFE),
            "SELECT n FROM (SELECT COUNT(DISTINCT customer_unique_id) AS n FROM customers) t",
        )
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - SELECT 항목 안의 서브쿼리로 고른 개인정보 열은 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT (SELECT MAX(customer_zip_code_prefix) FROM customers) AS z")
    }

    @Test
    fun `F-003 서브쿼리를 거친 열 - UNION은 어느 쪽이든 개인정보면 그 열을 가린다`() {
        assertTrace(
            listOf(ItemPii.PII),
            "SELECT customer_state FROM customers UNION SELECT customer_zip_code_prefix FROM customers",
        )
    }

    @Test
    fun `F-003 어디서 온 열인지 알 수 없음 - 별표를 거친 서브쿼리의 개인정보 이름 열은 가린다`() {
        assertTrace(listOf(ItemPii.PII), "SELECT customer_zip_code_prefix FROM (SELECT * FROM customers) t")
    }
}
