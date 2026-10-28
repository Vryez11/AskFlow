package io.askflow.api.connection

import io.askflow.pipeline.datasource.TargetDbConfig
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * 분석 대상 DB 설정 `askflow.target-db.*` (design 2장). pipeline의 [TargetDbConfig]로 옮겨 쓴다.
 * 상한이 0 이하면 설정 키를 담아 시작을 멈춘다.
 */
@Validated
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
    @field:Positive
    val maxRows: Int = TargetDbConfig.DEFAULT_MAX_ROWS,
    @field:DurationMin(millis = 1)
    val queryTimeout: Duration = TargetDbConfig.DEFAULT_QUERY_TIMEOUT,
) {
    // 로그나 오류 메시지에 비밀번호가 찍히지 않게 한다
    override fun toString(): String = "TargetDbProperties(${toConfig()})"

    fun toConfig(): TargetDbConfig =
        TargetDbConfig(
            url = url,
            schema = schema,
            username = username,
            password = password,
            allowedTables = allowedTables,
            piiColumns = piiColumns,
            maxRows = maxRows,
            queryTimeout = queryTimeout,
        )
}
