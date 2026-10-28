package io.askflow.pipeline.datasource

import java.time.Duration

/**
 * 성공한 실행의 결과 (safe-sql-execution "실행 결과 형식"). 화면용이라 개인정보 열도 남아 있다.
 * LLM에는 [forLlm]으로 만든 [LlmSafeResult]만 보낸다 (design 7장).
 */
class QueryResult(
    val columns: List<ResultColumn>,
    val rows: List<List<Any?>>,
    val elapsed: Duration,
    /** LIMIT을 붙인 뒤 실제로 실행한 SQL */
    val executedSql: String,
) {
    val rowCount: Int get() = rows.size

    fun forLlm(): LlmSafeResult = TODO()
}

data class ResultColumn(
    val name: String,
    /** 개인정보 컬럼에서 나온 값인지 */
    val pii: Boolean,
)

/**
 * 개인정보 열을 뺀, LLM에 보낼 수 있는 결과. [QueryResult.forLlm]으로만 만든다.
 */
class LlmSafeResult internal constructor(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    /** 뺀 개인정보 열의 이름. 값은 남기지 않는다 */
    val removedColumns: List<String>,
)
