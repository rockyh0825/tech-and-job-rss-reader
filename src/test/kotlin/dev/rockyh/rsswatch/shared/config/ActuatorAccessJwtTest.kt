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
 * AccessJwtFilter 有効時(rss-watch.access.aud 設定時)の actuator 除外を検証する(要件 3)。
 *
 * localhost の Prometheus コンテナは Cloudflare Access を経由できず Cf-Access-Jwt-Assertion を
 * 持たないため、/actuator/health・/actuator/prometheus だけはヘッダなしでも通す必要がある。
 * JWKS の取得は初回トークン検証まで遅延するため、ヘッダなしのテストはネットワークに出ない。
 *
 * フルコンテキストの隔離は [ActuatorEndpointsTest] と同じ(Postgres コンテナ / Kafka 到達不能 / fetch 停止)。
 */
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "rss-watch.fetch.initial-delay-ms=3600000",
        "spring.kafka.bootstrap-servers=localhost:1",
        "rss-watch.access.team-domain=myteam.cloudflareaccess.com",
        "rss-watch.access.aud=aud-tag-123",
    ],
)
@Import(PostgresTestConfiguration::class)
class ActuatorAccessJwtTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun prometheus_endpoint_returns_200_without_access_jwt_header() {
        // Arrange & Act: Prometheus の scrape 相当(ヘッダなし)
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        // Assert(要件 3.1)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun health_endpoint_returns_200_without_access_jwt_header() {
        // Arrange & Act: 死活監視(ヘッダなし)
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        // Assert(要件 3.1)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun api_report_returns_401_without_access_jwt_header() {
        // Arrange & Act: actuator 以外のパスは従来どおり検証対象のまま
        val response = restTemplate.getForEntity("/api/report", String::class.java)

        // Assert(要件 3.2)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
