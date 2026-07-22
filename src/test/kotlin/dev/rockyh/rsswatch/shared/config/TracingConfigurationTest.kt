package dev.rockyh.rsswatch.shared.config

import com.zaxxer.hikari.HikariDataSource
import dev.rockyh.rsswatch.testing.PostgresTestConfiguration
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.otel.bridge.OtelTracer
import javax.sql.DataSource
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Micrometer Tracing(OTel ブリッジ)+ JDBC 観測の配線を検証する(distributed-tracing spec 要件 1・2)。
 *
 * Spring Boot のテスト基盤は @SpringBootTest に management.tracing.enabled=false を自動適用するため、
 * @AutoConfigureObservability(metrics = false) で tracing のみ本番同等に有効化する。
 * 実 Tempo への送信はテストでは行わない(management.otlp.tracing.export.enabled=false で
 * OTLP エクスポータを無効化。design の Testing Strategy 参照)。
 *
 * フルコンテキストの隔離は既存 [dev.rockyh.rsswatch.RssWatchApplicationTest] と同じ:
 * - DB は共有 PostgreSQL コンテナ(PostgresTestConfiguration)
 * - Kafka は到達不能ポート(localhost:1)でローカル実ブローカーへの誤接続を防ぐ
 * - fetch の initial-delay を大きくして @Scheduled の実フィードアクセスを止める
 */
@AutoConfigureObservability(metrics = false)
@SpringBootTest(
    properties = [
        "rss-watch.fetch.initial-delay-ms=3600000",
        "spring.kafka.bootstrap-servers=localhost:1",
        "management.otlp.tracing.export.enabled=false",
    ],
)
@Import(PostgresTestConfiguration::class)
class TracingConfigurationTest {

    @Autowired
    lateinit var tracerProvider: ObjectProvider<Tracer>

    @Autowired
    lateinit var tracingProperties: TracingProperties

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun tracer_bean_is_otel_bridge_tracer() {
        // Arrange & Act: @SpringBootTest がコンテキストを起動し、micrometer-tracing-bridge-otel の
        // 自動構成(OtelAutoConfiguration)が Tracer Bean を配線する
        val tracer = tracerProvider.ifAvailable

        // Assert: OTel ブリッジの OtelTracer であること。actuator の NoopTracerAutoConfiguration が
        // fallback で配線する noop Tracer でも isNotNull は通ってしまうため、型まで検証する
        assertThat(tracer).isInstanceOf(OtelTracer::class.java)
    }

    @Test
    fun sampling_probability_is_bound_to_full_sampling() {
        // Arrange & Act: application.yml の management.tracing.sampling.probability が
        // TracingProperties に束縛される(既定は 0.1)

        // Assert: 全量サンプリング(要件 1.3。性能調査で「遅かったあのリクエスト」を欠けさせない)
        assertThat(tracingProperties.sampling.probability).isEqualTo(1.0f)
    }

    @Test
    fun datasource_is_proxied_for_jdbc_observation() {
        // Arrange & Act: datasource-micrometer-spring-boot の DataSourceObservationBeanPostProcessor が
        // DataSource Bean をプロキシでラップする(クエリ実行が Observation = SQL スパンになる。要件 2.1)

        // Assert: Bean は生の HikariDataSource ではなく、datasource-proxy のプロキシ
        // (ProxyJdbcObject はプロキシが実装する公開マーカーインターフェース)になっている
        assertThat(dataSource).isNotInstanceOf(HikariDataSource::class.java)
        assertThat(dataSource).isInstanceOf(ProxyJdbcObject::class.java)
    }
}
