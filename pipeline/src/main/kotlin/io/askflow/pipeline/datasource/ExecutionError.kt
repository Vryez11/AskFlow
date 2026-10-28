package io.askflow.pipeline.datasource

/**
 * 실행 오류 (safe-sql-execution "실행 오류 형식").
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
)

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
