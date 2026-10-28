package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.guard.ItemPii.SAFE
import io.askflow.pipeline.datasource.guard.ItemPii.STAR
import io.askflow.pipeline.datasource.guard.PiiSchemaObjects
import kotlin.test.Test
import kotlin.test.assertEquals

/** 메타데이터의 원래 테이블·컬럼이 실행할 때 읽은 컬럼 목록에 없으면 가린다 (F-003, design 7장 "판정 방향") */
class PiiColumnMarkerColumnsTest {
    private val marker =
        PiiColumnMarker(
            emptyList(),
            PiiSchemaObjects(
                views = emptyList(),
                generatedColumns = emptyList(),
                functions = emptyList(),
                columns = listOf("customers" to "customer_id", "customers" to "customer_state"),
            ),
        )

    @Test
    fun `F-003 확인하지 못한 이름 - 컬럼 목록에 없는 메타데이터는 가리고 목록에 있으면 대소문자만 무시한다`() {
        assertEquals(
            listOf(false, true, true),
            marker.mark(
                listOf(STAR),
                listOf(
                    ColumnOrigin("Customers", "CUSTOMER_STATE"),
                    ColumnOrigin("customers", "customer_unique_id"),
                    ColumnOrigin("sellers", "customer_id"),
                ),
            ),
        )
        assertEquals(listOf(false), marker.mark(listOf(SAFE), listOf(ColumnOrigin("customers", "customer_id"))))
    }
}
