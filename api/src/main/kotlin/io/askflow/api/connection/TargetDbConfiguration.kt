package io.askflow.api.connection

import io.askflow.pipeline.datasource.DataSourceConnector
import io.askflow.pipeline.datasource.TargetDbConfig
import io.askflow.pipeline.datasource.mysql.MySqlConnector
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 설정을 바인딩해 커넥터를 빈으로 등록한다 (design 1·2장). DB에 닿지 않아도 앱은 뜬다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TargetDbProperties::class)
class TargetDbConfiguration {
    @Bean
    fun targetDbConfig(properties: TargetDbProperties): TargetDbConfig = properties.toConfig()

    @Bean
    fun dataSourceConnector(config: TargetDbConfig): DataSourceConnector = MySqlConnector(config)
}
