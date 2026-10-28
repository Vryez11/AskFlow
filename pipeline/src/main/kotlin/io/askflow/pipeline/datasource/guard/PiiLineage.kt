package io.askflow.pipeline.datasource.guard

/**
 * 해석 트리로 최상위 SELECT 항목마다 개인정보 컬럼에서 나온 열인지 추적한다 (design 7장).
 * 별칭으로 테이블까지 풀고, 서브쿼리·CTE는 그 안의 SELECT 항목으로 이어 간다.
 * COUNT 안에서만 쓴 경우만 예외이고, 풀 수 없으면 개인정보로 본다(fail-closed).
 */
class PiiLineage(
    /** "table.column" 형식. 대소문자를 무시하고 비교한다 */
    private val piiColumns: Collection<String>,
) {
    /** [SqlGuard]를 통과한 SQL을 받아, 최상위 SELECT 항목 순서대로 판정을 돌려준다 */
    fun trace(sql: String): List<ItemPii> = TODO()
}

enum class ItemPii {
    /** 개인정보 컬럼에서 나왔거나 어디서 왔는지 알 수 없다 */
    PII,

    /** 개인정보 컬럼 값이 담기지 않는다 */
    SAFE,

    /** `*`, `t.*`. 펼친 열은 JDBC 메타데이터로 판정하고, 메타데이터도 없으면 가린다 */
    STAR,
}
