package dev.rockyh.rsswatch

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// initial-delay を大きくして、テスト中に @Scheduled の巡回(実フィードへのアクセス)が走らないようにする
@SpringBootTest(properties = ["rss-watch.fetch.initial-delay-ms=3600000"])
class RssWatchApplicationTest {

    @Test
    fun context_loads_without_errors() {
        // Arrange & Act: @SpringBootTest がアプリケーションコンテキストを起動する
        // Assert: 起動に失敗すればテストが失敗する
    }
}
