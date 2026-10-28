package io.askflow.pipeline.datasource

import java.time.Duration

/**
 * 분석 대상 DB 설정 (design 2장). api가 `askflow.target-db.*`를 바인딩해 만든다.
 * pipeline은 설정 파일 형식을 모르고 이 값만 받는다.
 */
data class TargetDbConfig(
    val url: String,
    val schema: String,
    val username: String,
    val password: String,
    /** 비우면 스키마의 모든 테이블과 뷰 */
    val allowedTables: List<String> = emptyList(),
    /** "table.column" 형식 */
    val piiColumns: List<String> = emptyList(),
    val maxRows: Int = DEFAULT_MAX_ROWS,
    val queryTimeout: Duration = DEFAULT_QUERY_TIMEOUT,
) {
    // 로그나 오류 메시지에 비밀번호가 찍히지 않게 한다
    override fun toString(): String =
        "TargetDbConfig(url=$url, schema=$schema, username=$username, password=***, " +
            "allowedTables=$allowedTables, piiColumns=$piiColumns, maxRows=$maxRows, queryTimeout=$queryTimeout)"

    companion object {
        const val DEFAULT_MAX_ROWS = 1000
        val DEFAULT_QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
