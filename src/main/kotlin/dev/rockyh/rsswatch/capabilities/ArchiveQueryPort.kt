package dev.rockyh.rsswatch.capabilities

import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem

/** 求人で言及された技術キーワードとその言及求人数。 */
data class TechMention(
    val keyword: String,
    val mentionCount: Int,
)

/**
 * 蓄積済み item への問い合わせの Port(feature 間境界)。
 *
 * - 依存する側: report/application(BuildReportUseCase がレポート組み立てに使う)
 * - 実装する側: archive/application(ArchiveQueryPortImpl)
 */
interface ArchiveQueryPort {

    /** 直近 [days] 日の求人で言及された技術キーワードを言及求人数の降順で返す。 */
    fun techRanking(days: Int): List<TechMention>

    /** 直近 [days] 日の指定カテゴリの item を新しい順で返す。 */
    fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem>

    /** 直近 [days] 日の、指定キーワードが付いた指定カテゴリの item を新しい順で返す。 */
    fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem>
}
