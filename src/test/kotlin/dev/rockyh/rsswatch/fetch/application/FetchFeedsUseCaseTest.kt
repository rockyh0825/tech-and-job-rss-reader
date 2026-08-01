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
import kotlin.test.assertFailsWith
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
    fun skips_entries_published_before_the_cutoff() {
        val oldEntry = entry.copy(guid = "guid-old", title = "古い記事", publishedAt = Instant.parse("2026-06-28T11:59:59Z"))
        val recentEntry = entry.copy(guid = "guid-recent", publishedAt = Instant.parse("2026-07-04T12:00:00Z"))
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(oldEntry, recentEntry)))
        val publisher = RecordingPublisher()
        val port = RecordingKeywordPort()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                parser,
                publisher,
                port,
                clock,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-recent"), publisher.published.map { it.guid })
        // 足切りしたエントリはキーワード抽出も行わない(冗長な抽出の削減が本変更の目的の一つ)
        assertEquals(1, port.extractedTexts.size)
        assertTrue("古い記事" !in port.extractedTexts.single(), "stale entry should not be keyword-extracted")
    }

    @Test
    fun rejects_non_positive_max_entry_age() {
        assertFailsWith<IllegalArgumentException> {
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                FakeFeedParser(),
                RecordingPublisher(),
                RecordingKeywordPort(),
                clock,
                maxEntryAgeDays = 0,
            )
        }
    }

    @Test
    fun publishes_entries_published_exactly_at_the_cutoff() {
        val boundaryEntry = entry.copy(guid = "guid-boundary", publishedAt = Instant.parse("2026-06-28T12:00:00Z"))
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(boundaryEntry)))
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-boundary"), publisher.published.map { it.guid })
    }

    @Test
    fun publishes_entries_without_published_at() {
        val undatedEntry = entry.copy(guid = "guid-undated", publishedAt = null)
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(undatedEntry)))
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-undated"), publisher.published.map { it.guid })
    }

    @Test
    fun applies_the_configured_max_entry_age() {
        val twoDaysOld = entry.copy(guid = "guid-2d", publishedAt = Instant.parse("2026-07-03T12:00:00Z"))
        val halfDayOld = entry.copy(guid = "guid-12h", publishedAt = Instant.parse("2026-07-05T00:00:00Z"))
        val parser = FakeFeedParser(entriesByFeed = mapOf("Zenn" to listOf(twoDaysOld, halfDayOld)))
        val publisher = RecordingPublisher()
        val useCase =
            FetchFeedsUseCase(
                FakeFeedConfigSource(listOf(techFeed)),
                parser,
                publisher,
                RecordingKeywordPort(),
                clock,
                maxEntryAgeDays = 1,
            )

        useCase.fetchAll()

        assertEquals(listOf("guid-12h"), publisher.published.map { it.guid })
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
