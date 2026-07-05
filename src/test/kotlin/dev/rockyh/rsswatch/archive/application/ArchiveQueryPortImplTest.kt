package dev.rockyh.rsswatch.archive.application

import dev.rockyh.rsswatch.archive.domain.ItemQueries
import dev.rockyh.rsswatch.archive.domain.TechRankingEntry
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ArchiveQueryPortImplTest {

    private fun rssItem(guid: String, category: String = "tech"): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = category,
            title = "title",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = null,
            fetchedAt = Instant.parse("2026-07-01T00:00:00Z"),
            keywords = emptyList(),
        )

    private class FakeItemQueries : ItemQueries {
        override fun techRanking(days: Int): List<TechRankingEntry> = listOf(TechRankingEntry("Kotlin", 3))

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> = emptyList()

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> = emptyList()
    }

    @Test
    fun maps_tech_ranking_entries_to_tech_mentions() {
        val port = ArchiveQueryPortImpl(FakeItemQueries())

        val ranking = port.techRanking(days = 7)

        assertEquals(listOf(TechMention("Kotlin", 3)), ranking)
    }
}
