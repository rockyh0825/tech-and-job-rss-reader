package dev.rockyh.rsswatch.shared.config

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.spring.jdbc.SpringJdbcKueryClient
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KueryClientConfig {

    @Bean
    fun kueryClient(dataSource: DataSource): KueryBlockingClient =
        SpringJdbcKueryClient.builder()
            .dataSource(dataSource)
            .build()
}
