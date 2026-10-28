package io.askflow.pipeline.datasource

/**
 * 실행 오류 (safe-sql-execution "실행 오류 형식"). 화면용이라 DB 오류의 원인에 MySQL 메시지 원문이 남는다.
 * LLM에는 [forLlm]으로 만든 [LlmSafeError]만 보낸다 (design 7장).
 * 메시지와 원인에는 비밀번호를 넣지 않는다.
 */
data class ExecutionError(
    val kind: ExecutionErrorKind,
    /** 한국어 메시지 */
    val message: String,
    /** 무엇에 걸렸나. 거부한 테이블·함수 이름, 상한 값, DB가 알려 준 오류 내용 */
    val cause: String,
    /** 확인할 곳. 설정 키나 확인할 화면 */
    val checkPoints: List<String>,
    /** DB 오류일 때 DB가 알려 준 오류 코드 */
    val dbErrorCode: Int? = null,
    /** DB 오류일 때 SQLState */
    val sqlState: String? = null,
) {
    /** DB 오류는 원인(MySQL 메시지 원문)을 버리고, 검사·상한 오류는 원인을 남긴다 */
    fun forLlm(): LlmSafeError =
        LlmSafeError(
            kind = kind,
            message = message,
            cause = if (kind == ExecutionErrorKind.DB_ERROR) null else cause,
            dbErrorCode = dbErrorCode,
            sqlState = sqlState,
        )
}

/**
 * LLM에 보낼 수 있는 실행 오류. [ExecutionError.forLlm]으로만 만든다.
 * DB 오류의 원문은 컬럼 값을 담을 수 있어 버린다.
 */
class LlmSafeError internal constructor(
    val kind: ExecutionErrorKind,
    val message: String,
    /** SQL과 설정에서 온 원인(거부한 테이블·함수 이름, 상한 값). DB 오류면 없다 */
    val cause: String?,
    val dbErrorCode: Int?,
    val sqlState: String?,
) {
    /** LLM 프롬프트에 넣는 한 줄. 예: "DB 오류: DB가 SQL을 실행하지 못했습니다. (오류 코드 1772, SQLState HY000)" */
    override fun toString(): String {
        val details =
            listOfNotNull(
                cause?.let { "원인 $it" },
                dbErrorCode?.let { "오류 코드 $it" },
                sqlState?.let { "SQLState $it" },
            )
        val suffix = if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
        return "${kind.label}: $message$suffix"
    }
}

enum class ExecutionErrorKind(
    val label: String,
) {
    NOT_SELECT("SELECT가 아닌 SQL"),
    MULTIPLE_STATEMENTS("여러 문"),
    PARSE_FAILED("SQL 해석 실패"),
    TABLE_NOT_ALLOWED("허용하지 않은 테이블"),
    FUNCTION_NOT_ALLOWED("허용하지 않은 함수"),
    ROW_LIMIT_EXCEEDED("행 수 상한 초과"),
    TIMEOUT("실행 시간 상한 초과"),
    CONNECTION_FAILED("연결 실패"),
    DB_ERROR("DB 오류"),
}
