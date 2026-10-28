package io.askflow.pipeline.datasource.guard

/**
 * 실행마다 `information_schema`에서 읽는 스키마 목록 (design 7장).
 * 뷰, 생성 컬럼, 저장 함수는 정의를 따라가지 않고 개인정보로 보고, 컬럼 목록에 없는 이름은 확인하지 못한 것으로 본다.
 * 대소문자만 무시하고 비교한다. 테이블과 컬럼은 이름에 점이 들어갈 수 있어 쌍으로 다룬다.
 */
class PiiSchemaObjects(
    views: Collection<String>,
    /** (테이블, 컬럼) */
    generatedColumns: Collection<Pair<String, String>>,
    functions: Collection<String>,
    /** (테이블, 컬럼). null이면 컬럼 목록을 확인하지 않는다(해석 트리만 보는 단위 테스트용). 커넥터는 늘 넘긴다 */
    columns: Collection<Pair<String, String>>? = null,
) {
    private val views = views.map { it.lowercase() }.toSet()
    private val generatedColumns = generatedColumns.map { (table, column) -> table.lowercase() to column.lowercase() }.toSet()
    private val functions = functions.map { it.lowercase() }.toSet()
    private val columns = columns?.map { (table, column) -> table.lowercase() to column.lowercase() }?.toSet()

    fun isView(table: String): Boolean = table.lowercase() in views

    fun isGenerated(
        table: String,
        column: String,
    ): Boolean = (table.lowercase() to column.lowercase()) in generatedColumns

    fun isStoredFunction(name: String): Boolean = name.lowercase() in functions

    /** 읽은 컬럼 목록에 있는 이름인지. 대소문자만 무시하니 그 밖에 조금이라도 다른 이름은 확인하지 못한 것이 된다 */
    fun isKnownColumn(
        table: String,
        column: String,
    ): Boolean = columns == null || (table.lowercase() to column.lowercase()) in columns

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
