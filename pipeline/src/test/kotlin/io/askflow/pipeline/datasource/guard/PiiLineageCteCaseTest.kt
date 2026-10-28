package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.ItemPii.PII
import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import kotlin.test.Test
import kotlin.test.assertEquals

/** 이름이 정확히 같은 CTE가 있어도 대소문자만 다른 CTE가 함께 보이면 가린다 (F-003, design 7장 "출처 이름", 7.1 최종 검토 막음) */
class PiiLineageCteCaseTest {
    private val objects =
        PiiSchemaObjects(
            views = emptyList(),
            generatedColumns = emptyList(),
            functions = emptyList(),
            columns =
                listOf(
                    "orders" to "order_id",
                    "customers" to "customer_zip_code_prefix",
                ),
        )
    private val lineage = PiiLineage(listOf("customers.customer_zip_code_prefix"), objects)

    private fun assertTrace(
        expected: List<ItemPii>,
        sql: String,
    ) {
        assertEquals(expected, lineage.trace(sql), "SQL: $sql")
    }

    @Test
    fun `F-003 대소문자만 다른 CTE가 같은 WITH에 함께 보이면 정확히 같은 이름의 참조도 가린다`() {
        assertTrace(listOf(SAFE), "WITH t AS (SELECT order_id FROM orders) SELECT order_id FROM t")
        assertTrace(
            listOf(PII),
            "WITH t AS (SELECT order_id FROM orders), T AS (SELECT customer_zip_code_prefix AS order_id FROM customers) " +
                "SELECT order_id FROM t",
        )
    }

    @Test
    fun `F-003 바깥 범위의 CTE와 대소문자만 다른 안쪽 CTE가 함께 보이면 가린다`() {
        // 안쪽 범위에서 t는 바깥 CTE, T는 안쪽 CTE다. 서버 설정에 따라 MySQL이 더 가까운 T로 읽을 수 있다
        assertTrace(
            listOf(PII),
            "WITH t AS (SELECT order_id FROM orders) " +
                "SELECT x.order_id FROM (WITH T AS (SELECT customer_zip_code_prefix AS order_id FROM customers) " +
                "SELECT order_id FROM t) AS x",
        )
        assertTrace(
            listOf(PII),
            "WITH T AS (SELECT customer_zip_code_prefix AS order_id FROM customers) " +
                "SELECT x.order_id FROM (WITH t AS (SELECT order_id FROM orders) SELECT order_id FROM t) AS x",
        )
    }
}
