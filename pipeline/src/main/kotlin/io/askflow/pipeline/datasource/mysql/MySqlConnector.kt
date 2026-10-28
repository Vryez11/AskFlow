package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionStatus
import io.askflow.pipeline.datasource.DataSourceConnector
import io.askflow.pipeline.datasource.ExecutionOutcome
import io.askflow.pipeline.datasource.TargetDbConfig
import javax.sql.DataSource

/**
 * MySQL 커넥터 (design 1·4장). 풀은 HikariCP로 만든다.
 * 테스트는 internal 생성자로 가짜 [DataSource]를 넣는다.
 */
class MySqlConnector internal constructor(
    private val config: TargetDbConfig,
    private val dataSource: DataSource,
) : DataSourceConnector {
    constructor(config: TargetDbConfig) : this(config, createPool(config))

    override fun execute(sql: String): ExecutionOutcome = TODO()

    override fun checkConnection(): ConnectionStatus = TODO()

    internal companion object {
        // 통합 테스트가 커넥터와 같은 세션 설정으로 검사 없이 문장을 보내 볼 때도 쓴다
        fun createPool(config: TargetDbConfig): DataSource = TODO()
    }
}
