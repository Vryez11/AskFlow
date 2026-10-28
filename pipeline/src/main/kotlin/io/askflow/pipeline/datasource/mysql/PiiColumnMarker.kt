package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.guard.ItemPii
import io.askflow.pipeline.datasource.guard.PiiSchemaObjects
import io.askflow.pipeline.datasource.guard.piiColumnPairs

/**
 * 해석 트리 판정([ItemPii])과 JDBC 메타데이터를 합쳐 결과 열마다 개인정보인지 정한다 (design 7장).
 * 둘 중 하나라도 개인정보라고 하면 가린다. 메타데이터의 원래 테이블이 뷰이거나 생성 컬럼이면 개인정보다.
 */
internal class PiiColumnMarker(
    /** "table.column" 형식. 대소문자를 무시하고 비교한다 */
    piiColumns: Collection<String>,
    private val objects: PiiSchemaObjects = PiiSchemaObjects.NONE,
) {
    private val pii: Set<Pair<String, String>> = piiColumnPairs(piiColumns)

    /**
     * [origins]는 결과 열마다 메타데이터의 원래 테이블·컬럼이다. 계산한 열처럼 비어 있으면 null.
     * 항목을 결과 열에 맞출 수 없으면(별표가 둘 이상이거나, 별표 없이 수가 다르면) 모두 가린다.
     */
    fun mark(
        items: List<ItemPii>,
        origins: List<ColumnOrigin?>,
    ): List<Boolean> {
        val byItem = alignToColumns(items, origins.size) ?: return origins.map { true }
        return origins.indices.map { i ->
            val origin = origins[i]
            when (byItem[i]) {
                ItemPii.PII -> true
                ItemPii.SAFE -> origin != null && origin.isPii()
                // 별표로 펼친 열은 메타데이터로만 판정한다. 메타데이터가 없으면 가린다
                ItemPii.STAR -> origin == null || origin.isPii()
            }
        }
    }

    private fun ColumnOrigin.isPii(): Boolean =
        (table.lowercase() to column.lowercase()) in pii || objects.isView(table) || objects.isGenerated(table, column)

    /** 항목 판정을 결과 열 수만큼 펼친다. 맞출 수 없으면 null */
    private fun alignToColumns(
        items: List<ItemPii>,
        columnCount: Int,
    ): List<ItemPii>? {
        val stars = items.count { it == ItemPii.STAR }
        return when {
            stars == 0 -> items.takeIf { it.size == columnCount }
            stars > 1 -> null
            else -> {
                val starWidth = columnCount - (items.size - 1)
                if (starWidth < 0) return null
                val at = items.indexOf(ItemPii.STAR)
                items.subList(0, at) + List(starWidth) { ItemPii.STAR } + items.subList(at + 1, items.size)
            }
        }
    }
}

/** JDBC 메타데이터의 원래 테이블·컬럼 이름 */
internal data class ColumnOrigin(
    val table: String,
    val column: String,
)
