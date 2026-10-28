package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.guard.ItemPii.PII
import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import io.askflow.pipeline.datasource.guard.ItemPii.STAR
import kotlin.test.Test
import kotlin.test.assertEquals

/** 해석 트리 판정과 JDBC 메타데이터를 결과 열에 맞추는 규칙 (F-003, design 7장) */
class PiiColumnMarkerTest {
    private val marker = PiiColumnMarker(listOf("customers.customer_zip_code_prefix", "olist.Customers.CUSTOMER_UNIQUE_ID"))

    private val id = ColumnOrigin("customers", "customer_id")
    private val zip = ColumnOrigin("customers", "customer_zip_code_prefix")
    private val unique = ColumnOrigin("customers", "customer_unique_id")

    @Test
    fun `F-003 개인정보 열 - 해석 트리나 메타데이터 중 하나라도 개인정보면 가린다`() {
        assertEquals(listOf(true, true, false), marker.mark(listOf(PII, SAFE, SAFE), listOf(id, zip, id)))
    }

    @Test
    fun `F-003 개인정보 열 - 계산한 열은 메타데이터가 비어 해석 트리 판정을 따른다`() {
        assertEquals(listOf(false, true), marker.mark(listOf(SAFE, PII), listOf(null, null)))
    }

    @Test
    fun `F-003 개인정보 열 - 별표는 펼친 열 수만큼 메타데이터로 판정하고 메타데이터가 없으면 가린다`() {
        assertEquals(
            listOf(false, false, true, true, true, false),
            marker.mark(listOf(SAFE, STAR, SAFE), listOf(id, id, unique, zip, null, id)),
        )
    }

    @Test
    fun `F-003 개인정보 열 - 메타데이터 이름은 대소문자를 무시하고 스키마 접두어를 뺀 설정과 비교한다`() {
        assertEquals(listOf(true), marker.mark(listOf(STAR), listOf(ColumnOrigin("CUSTOMERS", "Customer_Unique_Id"))))
    }

    @Test
    fun `F-003 개인정보 열 - 항목을 결과 열에 맞출 수 없으면 모두 가린다`() {
        // 별표 없이 수가 다름
        assertEquals(listOf(true, true), marker.mark(listOf(SAFE), listOf(id, id)))
        // 별표가 둘
        assertEquals(listOf(true, true, true), marker.mark(listOf(STAR, STAR), listOf(id, id, id)))
        // 별표를 빼도 항목이 열보다 많음
        assertEquals(listOf(true), marker.mark(listOf(SAFE, STAR, SAFE), listOf(id)))
    }
}
