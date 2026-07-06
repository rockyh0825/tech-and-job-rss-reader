package dev.rockyh.rsswatch.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * テスト全体で共有する PostgreSQL コンテナ(singleton container パターン)。
 *
 * テストクラスごとにコンテナを起動すると遅いため、最初に使われた時点で 1 つだけ起動し
 * JVM 終了まで使い回す(Testcontainers の Ryuk が終了後に回収する)。
 * DB を共有するため、テスト間のデータ独立が必要なクラスは自分でテーブルを空にすること。
 *
 * 注意: [PostgresTestConfiguration] 経由で Spring bean としても公開しているため、
 * いずれかの Spring コンテキストが close されると(`@DirtiesContext`、コンテキストキャッシュの
 * eviction、起動途中の失敗など)TestcontainersLifecycleBeanPostProcessor がこのコンテナを
 * stop し、以降の DB テストが壊れる。現状はコンテキスト数が少なくキャッシュ上限に達しないため、
 * 実質 JVM 終了時のみ close される前提で運用している。
 */
object SharedPostgresContainer {
    val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).also { it.start() }
    }
}

/**
 * Spring コンテキストを立てるテスト用の設定。`@Import` すると共有コンテナが
 * `@ServiceConnection` 経由で DataSource に配線される(application.yml の datasource を上書き)。
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> = SharedPostgresContainer.instance
}
