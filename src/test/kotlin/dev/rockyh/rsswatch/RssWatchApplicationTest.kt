package dev.rockyh.rsswatch

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// initial-delay を大きくして、テスト中に @Scheduled の巡回(実フィードへのアクセス)が走らないようにする。
// SQLite はリポジトリ直下を汚さないよう build/ 配下に作る。
// bootstrap-servers は到達不能ポートにして、ローカルの Kafka(localhost:9092)に
// group "sink" として join し実メッセージを消費してしまう事故を防ぐ(接続失敗は
// バックグラウンドリトライになるだけで、コンテキスト起動は失敗しない)
@SpringBootTest(
    properties = [
        "rss-watch.fetch.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:sqlite:build/rss-watch-context-test.db",
        "spring.kafka.bootstrap-servers=localhost:1",
    ],
)
class RssWatchApplicationTest {

    @Test
    fun context_loads_without_errors() {
        // Arrange & Act: @SpringBootTest がアプリケーションコンテキストを起動する
        // Assert: 起動に失敗すればテストが失敗する
    }
}
