package dev.rockyh.rsswatch.archive.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.core.list
import dev.rockyh.rsswatch.archive.domain.ItemQueries
import dev.rockyh.rsswatch.archive.domain.ItemStore
import dev.rockyh.rsswatch.archive.domain.TechRankingEntry
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * PostgreSQL への冪等書き込みと集計クエリ。方言依存(ON CONFLICT 等)をこのクラスに閉じ込める。
 * スキーマは Flyway(db/migration)が唯一の正本。
 *
 * - タイムスタンプは TIMESTAMPTZ(マイクロ秒精度)。[Instant] は UTC の [OffsetDateTime] に
 *   変換してバインドし、読み出しも [OffsetDateTime] から [Instant] に戻す(JDBC 4.2 の標準マッピング)。
 *   ナノ秒は格納時に最近接のマイクロ秒へ丸められる
 * - 「直近 N 日」の判定は published_at、なければ fetched_at で行う
 * - keywords は item_keywords テーブル(guid + keyword の複合主キー)に正規化して保存するため、
 *   読み出し時はアルファベット順になる(投入時の順序は保持しない)
 */
@Repository
class RssItemRepository(
    private val kueryClient: KueryBlockingClient,
    private val clock: Clock = Clock.systemUTC(),
) : ItemStore, ItemQueries {

    /** 新規 guid の item のみ挿入し、挿入した件数を返す(既存 guid は無視)。 */
    @Transactional
    override fun insertIgnore(items: List<RssItem>): Int {
        var inserted = 0
        for (item in items) {
            val publishedAt = item.publishedAt?.let(::toUtcOffset)
            val fetchedAt = toUtcOffset(item.fetchedAt)
            val rows =
                kueryClient
                    .sql {
                        +"""
                        INSERT INTO items
                            (guid, feed_name, category, title, url, summary, published_at, fetched_at)
                        VALUES
                            (${item.guid}, ${item.feedName}, ${item.category}, ${item.title},
                             ${item.url}, ${item.summary}, $publishedAt, $fetchedAt)
                        ON CONFLICT (guid) DO NOTHING
                        """
                    }.rowsUpdated()
            if (rows > 0) inserted++
            for (keyword in item.keywords) {
                kueryClient
                    .sql {
                        +"""
                        INSERT INTO item_keywords (guid, keyword) VALUES (${item.guid}, $keyword)
                        ON CONFLICT (guid, keyword) DO NOTHING
                        """
                    }.rowsUpdated()
            }
        }
        return inserted
    }

    /** 直近 [days] 日の求人([ItemCategory.JOBS])で言及された技術キーワードを言及求人数の降順で返す。 */
    override fun techRanking(days: Int): List<TechRankingEntry> {
        val cutoff = cutoff(days)
        val jobsCategory = ItemCategory.JOBS.value
        return kueryClient
            .sql {
                +"""
                SELECT k.keyword AS keyword, COUNT(*) AS mentionCount
                FROM item_keywords k
                JOIN items i ON i.guid = k.guid
                WHERE i.category = $jobsCategory
                  AND COALESCE(i.published_at, i.fetched_at) >= $cutoff
                GROUP BY k.keyword
                ORDER BY mentionCount DESC, k.keyword ASC
                """
            }.list<TechRankingRow>()
            .map { TechRankingEntry(it.keyword, it.mentionCount.toInt()) }
    }

    /** 直近 [days] 日の指定カテゴリの item を新しい順で返す。 */
    override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> {
        val cutoff = cutoff(days)
        val categoryValue = category.value
        val rows =
            kueryClient
                .sql {
                    +"""
                    SELECT i.guid AS guid, i.feed_name AS feedName, i.category AS category,
                           i.title AS title, i.url AS url, i.summary AS summary,
                           i.published_at AS publishedAt, i.fetched_at AS fetchedAt
                    FROM items i
                    WHERE i.category = $categoryValue
                      AND COALESCE(i.published_at, i.fetched_at) >= $cutoff
                    ORDER BY COALESCE(i.published_at, i.fetched_at) DESC, i.guid ASC
                    """
                }.list<ItemRow>()
        return assembleItems(rows)
    }

    /** 直近 [days] 日の、指定キーワードが付いた指定カテゴリの item を新しい順で返す。 */
    override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
        itemsByKeywords(listOf(keyword), category, days)[keyword].orEmpty()

    /**
     * 直近 [days] 日の、[keywords] の各キーワードが付いた指定カテゴリの item を
     * 1 クエリで一括取得し、キーワードごとに新しい順で返す(N+1 回避)。
     */
    override fun itemsByKeywords(
        keywords: List<String>,
        category: ItemCategory,
        days: Int,
    ): Map<String, List<RssItem>> {
        if (keywords.isEmpty()) return emptyMap()
        val cutoff = cutoff(days)
        val categoryValue = category.value
        val rows =
            kueryClient
                .sql {
                    +"""
                    SELECT k.keyword AS matchedKeyword, i.guid AS guid, i.feed_name AS feedName,
                           i.category AS category, i.title AS title, i.url AS url, i.summary AS summary,
                           i.published_at AS publishedAt, i.fetched_at AS fetchedAt
                    FROM items i
                    JOIN item_keywords k ON k.guid = i.guid
                    WHERE k.keyword IN ($keywords)
                      AND i.category = $categoryValue
                      AND COALESCE(i.published_at, i.fetched_at) >= $cutoff
                    ORDER BY COALESCE(i.published_at, i.fetched_at) DESC, i.guid ASC
                    """
                }.list<MatchedItemRow>()
        val items = assembleItems(rows.map { it.toItemRow() })
        val grouped = rows.zip(items).groupBy({ (row, _) -> row.matchedKeyword }, { (_, item) -> item })
        return keywords.associateWith { grouped[it].orEmpty() }
    }

    /** item_keywords を引いて RssItem に組み立てる(行順は保持)。 */
    private fun assembleItems(rows: List<ItemRow>): List<RssItem> {
        if (rows.isEmpty()) return emptyList()
        val guids = rows.map { it.guid }
        val keywordRows =
            kueryClient
                .sql {
                    +"SELECT guid, keyword FROM item_keywords WHERE guid IN ($guids) ORDER BY keyword"
                }.list<KeywordRow>()
        val keywordsByGuid = keywordRows.groupBy({ it.guid }, { it.keyword })
        return rows.map { row ->
            RssItem(
                guid = row.guid,
                feedName = row.feedName,
                category = row.category,
                title = row.title,
                url = row.url,
                summary = row.summary,
                publishedAt = row.publishedAt?.toInstant(),
                fetchedAt = row.fetchedAt.toInstant(),
                keywords = keywordsByGuid[row.guid].orEmpty(),
            )
        }
    }

    private fun cutoff(days: Int): OffsetDateTime = toUtcOffset(clock.instant().minus(Duration.ofDays(days.toLong())))

    private fun toUtcOffset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private data class ItemRow(
        val guid: String,
        val feedName: String,
        val category: String,
        val title: String,
        val url: String,
        val summary: String,
        val publishedAt: OffsetDateTime?,
        val fetchedAt: OffsetDateTime,
    )

    /** itemsByKeywords の 1 行(item の全カラム + マッチしたキーワード)。 */
    private data class MatchedItemRow(
        val matchedKeyword: String,
        val guid: String,
        val feedName: String,
        val category: String,
        val title: String,
        val url: String,
        val summary: String,
        val publishedAt: OffsetDateTime?,
        val fetchedAt: OffsetDateTime,
    ) {
        fun toItemRow(): ItemRow =
            ItemRow(guid, feedName, category, title, url, summary, publishedAt, fetchedAt)
    }

    private data class KeywordRow(val guid: String, val keyword: String)

    private data class TechRankingRow(val keyword: String, val mentionCount: Long)
}
