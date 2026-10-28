package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import io.askflow.pipeline.datasource.guard.ItemPii.STAR
import io.askflow.pipeline.datasource.guard.PiiSchemaObjects
import kotlin.test.Test
import kotlin.test.assertEquals

/** 메타데이터의 원래 테이블이 뷰이거나 생성 컬럼이면 개인정보다 (F-003, design 7장) */
class PiiColumnMarkerObjectsTest {
    private val marker =
        PiiColumnMarker(
            listOf("customers.customer_zip_code_prefix"),
            PiiSchemaObjects(
                views = listOf("customer_regions"),
                generatedColumns = listOf("customers" to "zip_area", "odd.table" to "c"),
                functions = emptyList(),
            ),
        )

    @Test
    fun `F-003 뷰에서 온 열 - 메타데이터의 테이블이 뷰면 가린다`() {
        assertEquals(listOf(true), marker.mark(listOf(SAFE), listOf(ColumnOrigin("Customer_Regions", "customer_id"))))
    }

    @Test
    fun `F-003 생성 컬럼 - 메타데이터가 생성 컬럼이면 가리고 같은 테이블의 다른 열은 남긴다`() {
        assertEquals(
            listOf(true, false),
            marker.mark(listOf(STAR), listOf(ColumnOrigin("customers", "ZIP_AREA"), ColumnOrigin("customers", "customer_id"))),
        )
    }

    @Test
    fun `F-003 생성 컬럼 - 이름에 점이 든 테이블과 컬럼을 쌍으로 비교한다`() {
        assertEquals(
            listOf(true, false),
            marker.mark(listOf(STAR), listOf(ColumnOrigin("odd.table", "c"), ColumnOrigin("odd", "table.c"))),
        )
    }
}
