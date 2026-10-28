package io.askflow.pipeline.datasource.guard

/**
 * 정의를 따라가지 않고 개인정보로 보는 스키마 객체: 뷰, 생성 컬럼, 저장 함수 (design 7장).
 * 실행마다 `information_schema`에서 읽는다. 대소문자를 무시하고 비교한다.
 * 테이블과 컬럼은 이름에 점이 들어갈 수 있어 쌍으로 다룬다.
 */
class PiiSchemaObjects(
    views: Collection<String>,
    /** (테이블, 컬럼) */
    generatedColumns: Collection<Pair<String, String>>,
    functions: Collection<String>,
) {
    private val views = views.map { it.lowercase() }.toSet()
    private val generatedColumns = generatedColumns.map { (table, column) -> table.lowercase() to column.lowercase() }.toSet()
    private val functions = functions.map { it.lowercase() }.toSet()

    fun isView(table: String): Boolean = table.lowercase() in views

    fun isGenerated(
        table: String,
        column: String,
    ): Boolean = (table.lowercase() to column.lowercase()) in generatedColumns

    fun isStoredFunction(name: String): Boolean = name.lowercase() in functions

    companion object {
        val NONE = PiiSchemaObjects(emptyList(), emptyList(), emptyList())
    }
}

/**
 * 설정의 개인정보 컬럼("table.column", 스키마를 붙여도 됨)을 (테이블, 컬럼) 쌍으로 바꾼다. 모두 소문자.
 * 설정 형식이 점으로 나누니 설정에 적는 이름에는 점을 쓸 수 없다.
 */
internal fun piiColumnPairs(piiColumns: Collection<String>): Set<Pair<String, String>> =
    piiColumns
        .map { it.lowercase() }
        .map { it.substringBeforeLast('.').substringAfterLast('.') to it.substringAfterLast('.') }
        .toSet()
