package dev.rockyh.rsswatch.fetch.application

import dev.rockyh.rsswatch.capabilities.KeywordExtractionPort
import dev.rockyh.rsswatch.fetch.domain.FeedConfigSource
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import dev.rockyh.rsswatch.fetch.domain.FeedParser
import dev.rockyh.rsswatch.fetch.domain.ItemPublisher
import dev.rockyh.rsswatch.fetch.domain.ParsedEntry
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 全フィードを巡回し、パース → キーワード抽出 → publish する(パイプラインの入口)。
 * 個別フィードの失敗はログを残してスキップし、残りのフィードの巡回を継続する(要件 1.3)。
 */
@Service
class FetchFeedsUseCase(
    private val feedConfigSource: FeedConfigSource,
    private val feedParser: FeedParser,
    private val itemPublisher: ItemPublisher,
    private val keywordExtractionPort: KeywordExtractionPort,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchAll() {
        feedConfigSource.feeds().forEach { feed ->
            try {
                fetchOne(feed)
            } catch (e: Exception) {
                log.warn("フィード「{}」の巡回に失敗したためスキップします: {}", feed.name, e.message, e)
            }
        }
    }

    private fun fetchOne(feed: FeedDefinition) {
        feedParser.parse(feed).forEach { entry ->
            itemPublisher.publish(toRssItem(feed, entry))
        }
    }

    private fun toRssItem(feed: FeedDefinition, entry: ParsedEntry): RssItem {
        val keywords = keywordExtractionPort.extract("${entry.title}\n${entry.summary}")
        return RssItem(
            guid = entry.guid,
            feedName = feed.name,
            category = feed.category.value,
            title = entry.title,
            url = entry.url,
            summary = entry.summary,
            publishedAt = entry.publishedAt,
            fetchedAt = clock.instant(),
            keywords = keywords.toList(),
        )
    }
}
