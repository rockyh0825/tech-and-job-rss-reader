package dev.rockyh.rsswatch.capabilities

import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem

/** 指定カテゴリの item で言及された技術キーワードとその言及 item 数。 */
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

    /** 直近 [days] 日の指定カテゴリの item で言及された技術キーワードを言及 item 数の降順で返す。 */
    fun techRanking(category: ItemCategory, days: Int): List<TechMention>

    /** 直近 [days] 日の指定カテゴリの item を新しい順で返す。 */
    fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem>

    /** 直近 [days] 日の、指定キーワードが付いた指定カテゴリの item を新しい順で返す。 */
    fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem>

    /**
     * 直近 [days] 日の、[keywords] の各キーワードが付いた指定カテゴリの item を一括で取得し、
     * キーワードごとに新しい順で返す(N+1 回避)。要求した全キーワードがキーに含まれる
     * (記事 0 件のキーワードは空リスト)。複数キーワードにマッチする item は各キーワードに重複して現れる。
     */
    fun itemsByKeywords(keywords: List<String>, category: ItemCategory, days: Int): Map<String, List<RssItem>>
}
