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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * SQLite への冪等書き込みと集計クエリ。SQLite 依存(INSERT OR IGNORE 等)をこのクラスに閉じ込める。
 * スキーマは Flyway(db/migration)が唯一の正本。
 *
 * - タイムスタンプは固定桁の ISO-8601 UTC(ナノ秒 9 桁)の TEXT で保存する。
 *   桁が固定なので辞書順比較 = 時系列比較が成立し、期間フィルタを文字列比較で行える
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
            val publishedAt = item.publishedAt?.let(::formatTimestamp)
            val fetchedAt = formatTimestamp(item.fetchedAt)
            val rows =
                kueryClient
                    .sql {
                        +"""
                        INSERT OR IGNORE INTO items
                            (guid, feed_name, category, title, url, summary, published_at, fetched_at)
                        VALUES
                            (${item.guid}, ${item.feedName}, ${item.category}, ${item.title},
                             ${item.url}, ${item.summary}, $publishedAt, $fetchedAt)
                        """
                    }.rowsUpdated()
            if (rows > 0) inserted++
            for (keyword in item.keywords) {
                kueryClient
                    .sql {
                        +"INSERT OR IGNORE INTO item_keywords (guid, keyword) VALUES (${item.guid}, $keyword)"
                    }.rowsUpdated()
            }
        }
        return inserted
    }

    /** 直近 [days] 日の求人(category = "jobs")で言及された技術キーワードを言及求人数の降順で返す。 */
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
                    ORDER BY COALESCE(i.published_at, i.fetched_at) DESC
                    """
                }.list<ItemRow>()
        return assembleItems(rows)
    }

    /** 直近 [days] 日の、指定キーワードが付いた指定カテゴリの item を新しい順で返す。 */
    override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> {
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
                    JOIN item_keywords k ON k.guid = i.guid
                    WHERE k.keyword = $keyword
                      AND i.category = $categoryValue
                      AND COALESCE(i.published_at, i.fetched_at) >= $cutoff
                    ORDER BY COALESCE(i.published_at, i.fetched_at) DESC
                    """
                }.list<ItemRow>()
        return assembleItems(rows)
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
                publishedAt = row.publishedAt?.let(Instant::parse),
                fetchedAt = Instant.parse(row.fetchedAt),
                keywords = keywordsByGuid[row.guid].orEmpty(),
            )
        }
    }

    private fun cutoff(days: Int): String = formatTimestamp(clock.instant().minus(Duration.ofDays(days.toLong())))

    private fun formatTimestamp(instant: Instant): String = TIMESTAMP_FORMAT.format(instant)

    private data class ItemRow(
        val guid: String,
        val feedName: String,
        val category: String,
        val title: String,
        val url: String,
        val summary: String,
        val publishedAt: String?,
        val fetchedAt: String,
    )

    private data class KeywordRow(val guid: String, val keyword: String)

    private data class TechRankingRow(val keyword: String, val mentionCount: Long)

    companion object {
        /** ナノ秒 9 桁固定の ISO-8601 UTC(Instant.toString() は桁が変動し辞書順が崩れるため使わない)。 */
        private val TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)
    }
}
