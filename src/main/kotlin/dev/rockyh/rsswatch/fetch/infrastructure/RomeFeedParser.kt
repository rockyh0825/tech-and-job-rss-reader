package dev.rockyh.rsswatch.fetch.infrastructure

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import dev.rockyh.rsswatch.fetch.domain.FeedParser
import dev.rockyh.rsswatch.fetch.domain.ParsedEntry
import java.net.URI
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Rome で RSS/Atom フィードを取得・パースする [FeedParser] 実装。
 * 接続・読み取りタイムアウトを設定し、応答しないフィードで巡回全体が止まらないようにする
 * (design.md Error Scenario 1: タイムアウトはフィード単位でスキップ)。
 */
@Component
class RomeFeedParser(
    @Value("\${rss-watch.fetch.connect-timeout-ms:10000}") private val connectTimeoutMs: Int = 10_000,
    @Value("\${rss-watch.fetch.read-timeout-ms:10000}") private val readTimeoutMs: Int = 10_000,
) : FeedParser {

    override fun parse(feed: FeedDefinition): List<ParsedEntry> {
        val connection =
            URI(feed.url).toURL().openConnection().apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
            }
        val syndFeed =
            connection.getInputStream().use { input ->
                XmlReader(input, connection.contentType, true).use { SyndFeedInput().build(it) }
            }
        return syndFeed.entries.map { entry ->
            ParsedEntry(
                guid = entry.uri ?: entry.link.orEmpty(),
                title = entry.title.orEmpty(),
                url = entry.link.orEmpty(),
                summary = entry.description?.value.orEmpty(),
                publishedAt = entry.publishedDate?.toInstant(),
            )
        }
    }

    companion object {
        /** Java デフォルト UA は Cloudflare 系フィードで 403 になりやすいため明示する。 */
        private const val USER_AGENT = "rss-watch/0.1 (+https://github.com/rockyh0825/tech-and-job-rss-reader)"
    }
}
