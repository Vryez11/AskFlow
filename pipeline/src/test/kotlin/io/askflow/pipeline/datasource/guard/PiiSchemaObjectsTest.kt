package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.ItemPii.PII
import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import io.askflow.pipeline.datasource.guard.ItemPii.STAR
import kotlin.test.Test
import kotlin.test.assertEquals

/** 뷰, 생성 컬럼, 저장 함수는 정의를 따라가지 않고 개인정보로 본다 (F-003, design 7장) */
class PiiSchemaObjectsTest {
    private val objects =
        PiiSchemaObjects(
            views = listOf("Customer_Regions"),
            generatedColumns = listOf("customers" to "zip_area"),
            functions = listOf("zip_of"),
        )
    private val lineage = PiiLineage(listOf("customers.customer_zip_code_prefix"), objects)

    @Test
    fun `F-003 뷰에서 온 열 - 해석 트리에서 뷰의 모든 열이 개인정보다`() {
        assertEquals(listOf(PII, PII), lineage.trace("SELECT customer_id, r.region_code FROM customer_regions r"))
        assertEquals(listOf(PII), lineage.trace("SELECT x FROM (SELECT customer_id AS x FROM customer_regions) t"))
    }

    @Test
    fun `F-003 뷰에서 온 열 - 뷰를 펼친 별표는 메타데이터에 맡기지 않고 가린다`() {
        assertEquals(listOf(PII), lineage.trace("SELECT * FROM CUSTOMER_REGIONS"))
        assertEquals(listOf(STAR), lineage.trace("SELECT * FROM customers"))
    }

    @Test
    fun `F-003 뷰의 행 수 - COUNT 안에서만 쓰면 남는다`() {
        assertEquals(listOf(SAFE, SAFE), lineage.trace("SELECT COUNT(*), COUNT(DISTINCT region_code) FROM customer_regions"))
    }

    @Test
    fun `F-003 생성 컬럼 - 생성 컬럼은 개인정보 컬럼이다`() {
        assertEquals(listOf(SAFE, PII), lineage.trace("SELECT customer_id, c.zip_area FROM customers c"))
        assertEquals(listOf(PII), lineage.trace("SELECT zip_area FROM customers JOIN orders USING (customer_id)"))
    }

    @Test
    fun `F-003 저장 함수 - 저장 함수나 스키마를 붙인 함수를 부른 항목은 개인정보다`() {
        assertEquals(
            listOf(PII, PII, SAFE),
            lineage.trace("SELECT ZIP_OF(customer_id), other.f(customer_id), UPPER(customer_id) FROM customers"),
        )
        assertEquals(listOf(PII), lineage.trace("SELECT z FROM (SELECT zip_of(customer_id) AS z FROM customers) t"))
    }

    @Test
    fun `F-003 스키마 객체가 없으면 판정이 그대로다`() {
        assertEquals(listOf(SAFE), PiiLineage(listOf("customers.customer_zip_code_prefix")).trace("SELECT zip_area FROM customers"))
    }
}
