package dev.rockyh.rsswatch

import dev.rockyh.rsswatch.testing.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// initial-delay を大きくして、テスト中に @Scheduled の巡回(実フィードへのアクセス)が走らないようにする。
// DB は共有の PostgreSQL コンテナ(PostgresTestConfiguration)に接続する。
// bootstrap-servers は到達不能ポートにして、ローカルの Kafka(localhost:9092)に
// group "sink" として join し実メッセージを消費してしまう事故を防ぐ(接続失敗は
// バックグラウンドリトライになるだけで、コンテキスト起動は失敗しない)
@SpringBootTest(
    properties = [
        "rss-watch.fetch.initial-delay-ms=3600000",
        "spring.kafka.bootstrap-servers=localhost:1",
    ],
)
@Import(PostgresTestConfiguration::class)
class RssWatchApplicationTest {

    @Test
    fun context_loads_without_errors() {
        // Arrange & Act: @SpringBootTest がアプリケーションコンテキストを起動する
        // Assert: 起動に失敗すればテストが失敗する
    }
}
