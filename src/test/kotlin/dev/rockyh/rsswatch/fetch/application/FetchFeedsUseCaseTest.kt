package dev.rockyh.rsswatch.fetch.application

import dev.rockyh.rsswatch.capabilities.KeywordExtractionPort
import dev.rockyh.rsswatch.fetch.domain.FeedConfigSource
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import dev.rockyh.rsswatch.fetch.domain.FeedParser
import dev.rockyh.rsswatch.fetch.domain.ItemPublisher
import dev.rockyh.rsswatch.fetch.domain.ParsedEntry
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FetchFeedsUseCaseTest {

    private val fixedNow = Instant.parse("2026-07-05T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val techFeed = FeedDefinition("Zenn", "https://zenn.dev/feed", ItemCategory.TECH)
    private val jobsFeed = FeedDefinition("HN Jobs", "https://hnrss.org/jobs", ItemCategory.JOBS)

    private val entry =
        ParsedEntry(
            guid = "guid-1",
            title = "Kotlinの話",
            url = "https://example.com/1",
            summary = "Spring Bootの概要",
            publishedAt = Instant.parse("2026-07-05T09:00:00Z"),
        )

    private class FakeFeedConfigSource(private val feedList: List<FeedDefinition>) : FeedConfigSource {
        override fun feeds(): List<FeedDefinition> = feedList
    }

    private class FakeFeedParser(
        private val entriesByFeed: Map<String, List<ParsedEntry>> = emptyMap(),
        private val failingFeeds: Set<String> = emptySet(),
    ) : FeedParser {
        override fun parse(feed: FeedDefinition): List<ParsedEntry> {
            if (feed.name in failingFeeds) throw RuntimeException("boom: ${feed.name}")
            return entriesByFeed[feed.name].orEmpty()
        }
    }

    private class RecordingPublisher(
        private val failingFeeds: Set<String> = emptySet(),
    ) : ItemPublisher {
        val published = mutableListOf<RssItem>()

        override fun publish(item: RssItem) {
            if (item.feedName in failingFeeds) throw RuntimeException("kafka down")
            published += item
        }
    }

    private class RecordingKeywordPort(
        private val keywords: Set<String> = emptySet(),
    ) : KeywordExtractionPort {
        val extractedTexts = mutableListOf<String>()

        override fun extract(text: String): Set<String> {
            extractedTexts += text
            return keywords
        }
    }

    @Test
    fun publishes_parsed_entries_as_rss_items_with_extracted_keywords() {
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(entry)))
        val publisher = RecordingPublisher()
        val port = RecordingKeywordPort(keywords = setOf("Kotlin", "Spring Boot"))
        val useCase =
            FetchFeedsUseCase(FakeFeedConfigSource(listOf(techFeed)), parser, publisher, port, clock)

        useCase.fetchAll()

        assertEquals(
            listOf(
                RssItem(
                    guid = "guid-1",
                    feedName = "Zenn",
                    category = "tech",
                    title = "Kotlinの話",
                    url = "https://example.com/1",
                    summary = "Spring Bootの概要",
                    publishedAt = Instant.parse("2026-07-05T09:00:00Z"),
                    fetchedAt = fixedNow,
                    keywords = listOf("Kotlin", "Spring Boot"),
                ),
            ),
            publisher.published,
        )
    }

    @Test
    fun extracts_keywords_from_title_and_summary() {
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(entry)))
        val port = RecordingKeywordPort()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                parser,
                RecordingPublisher(),
                port,
                clock,
            )

        useCase.fetchAll()

        assertEquals(1, port.extractedTexts.size)
        val text = port.extractedTexts.single()
        assertTrue("Kotlinの話" in text, "title should be in extraction target: $text")
        assertTrue("Spring Bootの概要" in text, "summary should be in extraction target: $text")
    }

    @Test
    fun sets_category_from_feed_definition() {
        val jobsEntry = entry.copy(guid = "guid-2")
        val parser = FakeFeedParser(entriesByFeed = mapOf("HN Jobs" to listOf(jobsEntry)))
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(jobsFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals("jobs", publisher.published.single().category)
    }

    @Test
    fun continues_remaining_feeds_when_one_feed_fails_to_parse() {
        val parser =
            FakeFeedParser(
                entriesByFeed = mapOf("HN Jobs" to listOf(entry.copy(guid = "guid-2"))),
                failingFeeds = setOf("Zenn"),
            )
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed, jobsFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-2"), publisher.published.map { it.guid })
    }

    @Test
    fun continues_remaining_feeds_when_publishing_fails_for_one_feed() {
        val parser =
            FakeFeedParser(
                entriesByFeed =
                    mapOf(
                        "Zenn" to listOf(entry),
                        "HN Jobs" to listOf(entry.copy(guid = "guid-2")),
                    ),
            )
        val publisher = RecordingPublisher(failingFeeds = setOf("Zenn"))
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed, jobsFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-2"), publisher.published.map { it.guid })
    }

    @Test
    fun publishes_nothing_when_no_feeds_are_configured() {
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(emptyList()),
                FakeFeedParser(),
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals(emptyList(), publisher.published)
    }
}
