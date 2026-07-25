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

    private inner class FakeItemQueries : ItemQueries {
        val techRankingCategories = mutableListOf<ItemCategory>()

        override fun techRanking(category: ItemCategory, days: Int): List<TechRankingEntry> {
            techRankingCategories += category
            return listOf(TechRankingEntry("Kotlin", 3))
        }

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> = emptyList()

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> = emptyList()

        override fun itemsByKeywords(
            keywords: List<String>,
            category: ItemCategory,
            days: Int,
        ): Map<String, List<RssItem>> = keywords.associateWith { listOf(rssItem("article-of-$it")) }
    }

    @Test
    fun maps_tech_ranking_entries_to_tech_mentions() {
        val port = ArchiveQueryPortImpl(FakeItemQueries())

        val ranking = port.techRanking(ItemCategory.TECH, days = 7)

        assertEquals(listOf(TechMention("Kotlin", 3)), ranking)
    }

    @Test
    fun delegates_tech_ranking_category_to_item_queries() {
        val itemQueries = FakeItemQueries()
        val port = ArchiveQueryPortImpl(itemQueries)

        port.techRanking(ItemCategory.JOBS, days = 7)

        assertEquals(listOf(ItemCategory.JOBS), itemQueries.techRankingCategories)
    }

    @Test
    fun delegates_items_by_keywords_to_item_queries() {
        val port = ArchiveQueryPortImpl(FakeItemQueries())

        val itemsByKeyword = port.itemsByKeywords(listOf("Kotlin"), ItemCategory.TECH, days = 7)

        assertEquals(mapOf("Kotlin" to listOf(rssItem("article-of-Kotlin"))), itemsByKeyword)
    }
}
