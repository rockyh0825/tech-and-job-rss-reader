package dev.rockyh.rsswatch.archive.application

import dev.rockyh.rsswatch.archive.domain.ItemQueries
import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.springframework.stereotype.Component

/** [ArchiveQueryPort] の実装。domain の [ItemQueries](実体は RssItemRepository)に委譲する。 */
@Component
class ArchiveQueryPortImpl(private val itemQueries: ItemQueries) : ArchiveQueryPort {

    override fun techRanking(days: Int): List<TechMention> =
        itemQueries.techRanking(days).map { TechMention(it.keyword, it.mentionCount) }

    override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> =
        itemQueries.itemsByCategory(category, days)

    override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
        itemQueries.itemsByKeyword(keyword, category, days)

    override fun itemsByKeywords(
        keywords: List<String>,
        category: ItemCategory,
        days: Int,
    ): Map<String, List<RssItem>> = itemQueries.itemsByKeywords(keywords, category, days)
}
