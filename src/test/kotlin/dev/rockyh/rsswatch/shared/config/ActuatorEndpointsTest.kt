package dev.rockyh.rsswatch.shared.config

import dev.rockyh.rsswatch.testing.PostgresTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

/**
 * Actuator + Micrometer(Prometheus レジストリ)のメトリクス公開を検証する(observability spec 要件 1・2)。
 *
 * Spring Boot は @SpringBootTest でメトリクスエクスポートを既定無効にする
 * (management.defaults.metrics.export.enabled=false)ため、@AutoConfigureObservability で
 * 本番同等に有効化して /actuator/prometheus を検証する。
 *
 * フルコンテキストの隔離は既存 [dev.rockyh.rsswatch.RssWatchApplicationTest] と同じ:
 * - DB は共有 PostgreSQL コンテナ(PostgresTestConfiguration)
 * - Kafka は到達不能ポート(localhost:1)でローカル実ブローカーへの誤接続を防ぐ
 * - fetch の initial-delay を大きくして @Scheduled の実フィードアクセスを止める
 */
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "rss-watch.fetch.initial-delay-ms=3600000",
        "spring.kafka.bootstrap-servers=localhost:1",
    ],
)
@Import(PostgresTestConfiguration::class)
class ActuatorEndpointsTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun prometheus_endpoint_returns_200_with_http_server_requests_metrics() {
        // Arrange: http.server.requests は最低 1 リクエスト完了後に登場するため、先に別エンドポイントを叩く
        restTemplate.getForEntity("/actuator/health", String::class.java)

        // Act
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        // Assert
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("http_server_requests_seconds")
    }

    @Test
    fun prometheus_output_contains_histogram_buckets_for_http_server_requests() {
        // Arrange: http.server.requests のメーター登場には最低 1 リクエストの完了が必要
        restTemplate.getForEntity("/actuator/health", String::class.java)

        // Act
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        // Assert: percentiles-histogram 有効時のみ histogram_quantile() 用のバケットが公開される(要件 1.3)
        assertThat(response.body).contains("http_server_requests_seconds_bucket")
    }

    @Test
    fun prometheus_output_contains_jvm_and_hikaricp_meters() {
        // Arrange & Act: JVM / HikariCP は自動計装なのでリクエスト実行なしでも登場する
        // (Kafka のリスナータイマーはブローカー到達不能設定下でコンテナ起動状況に依存し得るため
        //  テスト対象外。Task 3 の実地確認で担保する — design の Testing Strategy 参照)
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        // Assert(要件 1.4)
        assertThat(response.body).contains("jvm_memory_used_bytes")
        assertThat(response.body).contains("hikaricp_connections")
    }

    @Test
    fun health_endpoint_returns_200() {
        // Arrange & Act: health は DataSource チェックを含む(Postgres コンテナへの接続 UP が前提)
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        // Assert(要件 1.5)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun non_exposed_actuator_endpoints_return_404() {
        // Arrange & Act: expose は health,prometheus のみ(環境変数や Bean 定義を晒さない)
        val envResponse = restTemplate.getForEntity("/actuator/env", String::class.java)
        val beansResponse = restTemplate.getForEntity("/actuator/beans", String::class.java)

        // Assert(要件 2.2)
        assertThat(envResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(beansResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
