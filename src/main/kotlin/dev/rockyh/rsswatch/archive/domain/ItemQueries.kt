package dev.rockyh.rsswatch.archive.domain

import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem

/** 求人で言及された技術キーワードのランキング 1 行(言及求人数の降順)。 */
data class TechRankingEntry(
    val keyword: String,
    val mentionCount: Int,
)

/** 蓄積済み item の集計・検索の抽象(実装は infrastructure の RssItemRepository)。 */
interface ItemQueries {

    /** 直近 [days] 日の求人([ItemCategory.JOBS])で言及された技術キーワードを言及求人数の降順で返す。 */
    fun techRanking(days: Int): List<TechRankingEntry>

    /** 直近 [days] 日の指定カテゴリの item を新しい順で返す。 */
    fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem>

    /** 直近 [days] 日の、指定キーワードが付いた指定カテゴリの item を新しい順で返す。 */
    fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem>

    /**
     * 直近 [days] 日の、[keywords] の各キーワードが付いた指定カテゴリの item を一括で取得し、
     * キーワードごとに新しい順で返す(N+1 回避)。
     */
    fun itemsByKeywords(keywords: List<String>, category: ItemCategory, days: Int): Map<String, List<RssItem>>
}
