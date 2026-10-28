package io.askflow.pipeline.datasource.guard

/**
 * 설정의 개인정보 컬럼에 뷰와 생성 컬럼의 정의를 따라간 컬럼을 더한 "실제 개인정보 컬럼"을 만든다 (design 7장).
 * 해석 트리와 JDBC 메타데이터 판정 모두 이 집합을 쓴다.
 */
object PiiColumnResolver {
    /**
     * 돌려주는 집합은 "table.column" 형식이고 모두 소문자다.
     * 뷰가 뷰를, 생성 컬럼이 생성 컬럼을 쓸 수 있어 더 늘지 않을 때까지 반복한다.
     * 정의가 비었거나 해석하지 못하면 그 뷰의 모든 열, 그 생성 컬럼을 더한다(fail-closed).
     */
    fun resolve(
        piiColumns: Collection<String>,
        views: List<ViewDefinition>,
        generatedColumns: List<GeneratedColumn>,
    ): Set<String> {
        var current = piiColumns.map { it.lowercase() }.toSet()
        while (true) {
            val lineage = PiiLineage(current)
            val next =
                current +
                    generatedColumns.filter { isPii(lineage, it) }.map { key(it.table, it.column) } +
                    views.flatMap { view -> piiColumnsOf(lineage, view).map { key(view.name, it) } }
            if (next == current) return current
            current = next
        }
    }

    private fun key(
        table: String,
        column: String,
    ): String = "$table.$column".lowercase()

    // 생성식은 같은 테이블의 컬럼만 쓰니, 그 테이블에서 고르는 SELECT로 감싸 해석 트리로 본다
    private fun isPii(
        lineage: PiiLineage,
        generated: GeneratedColumn,
    ): Boolean {
        if (generated.expression.isBlank()) return true
        val table = generated.table.replace("`", "``")
        val items = runCatching { lineage.trace("SELECT (${generated.expression}) FROM `$table`") }.getOrNull()
        return items != listOf(ItemPii.SAFE)
    }

    /** 정의의 SELECT 항목을 뷰 열에 순서대로 맞춘다. 맞출 수 없으면 모든 열 */
    private fun piiColumnsOf(
        lineage: PiiLineage,
        view: ViewDefinition,
    ): List<String> {
        if (view.definition.isBlank()) return view.columns
        val items = runCatching { lineage.trace(view.definition) }.getOrNull()
        if (items == null || items.size != view.columns.size || ItemPii.STAR in items) return view.columns
        return view.columns.filterIndexed { i, _ -> items[i] == ItemPii.PII }
    }
}

/** `information_schema.VIEWS`의 정의와 `COLUMNS`의 열 이름(순서대로). 정의를 읽지 못했으면 [definition]이 비어 있다 */
data class ViewDefinition(
    val name: String,
    val definition: String,
    val columns: List<String>,
)

/** `information_schema.COLUMNS`의 `GENERATION_EXPRESSION` */
data class GeneratedColumn(
    val table: String,
    val column: String,
    val expression: String,
)
