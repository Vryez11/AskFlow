package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionError

/**
 * 실행 전 SQL 검사 (design 3장). 검사를 못 하면 막는다(fail-closed).
 * 순서: 실행 주석·힌트 → 해석 → 문 개수와 종류 → 테이블 → 함수 → LIMIT 다시 쓰기.
 */
class SqlGuard(
    private val schema: String,
    /** 대소문자를 무시하고 비교한다 */
    private val allowedTables: Set<String>,
    private val maxRows: Int,
) {
    fun check(sql: String): GuardResult = TODO()
}

sealed interface GuardResult {
    /** 검사를 통과했다. [sql]은 해석 트리를 다시 쓴, DB에 보낼 문장이다 */
    data class Passed(
        val sql: String,
    ) : GuardResult

    data class Rejected(
        val error: ExecutionError,
    ) : GuardResult
}
