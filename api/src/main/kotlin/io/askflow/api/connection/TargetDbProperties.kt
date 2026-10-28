package io.askflow.api.connection

import io.askflow.pipeline.datasource.TargetDbConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 분석 대상 DB 설정 `askflow.target-db.*` (design 2장). pipeline의 [TargetDbConfig]로 옮겨 쓴다.
 */
@ConfigurationProperties("askflow.target-db")
data class TargetDbProperties(
    val url: String,
    val schema: String,
    val username: String,
    val password: String,
    /** 비우면 스키마의 모든 테이블과 뷰 */
    val allowedTables: List<String> = emptyList(),
    /** "table.column" 형식 */
    val piiColumns: List<String> = emptyList(),
    val maxRows: Int = TargetDbConfig.DEFAULT_MAX_ROWS,
    val queryTimeout: Duration = TargetDbConfig.DEFAULT_QUERY_TIMEOUT,
) {
    fun toConfig(): TargetDbConfig = TODO()
}
