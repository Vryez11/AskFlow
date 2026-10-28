package io.askflow.pipeline.datasource

/**
 * 분석 대상 DB에 SQL을 실행하는 유일한 경로 (design 1장).
 * `execute` 말고 SQL 문자열을 받아 실행하는 공개 메서드는 두지 않는다.
 */
interface DataSourceConnector {
    /** 검사 → 다시 쓴 SQL 실행 → 결과 또는 오류 */
    fun execute(sql: String): ExecutionOutcome

    /** 연결, 권한, 허용 테이블 확인 */
    fun checkConnection(): ConnectionStatus
}
