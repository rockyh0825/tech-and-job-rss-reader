package dev.rockyh.rsswatch.notify.domain

import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant

/**
 * デイリーダイジェストに載せる「おすすめ N 件」を選抜する純 Kotlin のルール(domain)。
 *
 * Phase 1 の選抜ルール:
 *   1. 投稿済み guid を候補から除外する(日跨ぎの二重投稿防止)
 *   2. 人気フィード([popularFeeds] に feedName が含まれる)由来を優先する
 *   3. 同順位は publishedAt(無ければ fetchedAt)の新しい順でタイブレークする
 *   4. 上位 [limit] 件に絞る
 *
 * 将来 interest-recommend のスコアリングに差し替えられるよう、選抜ロジックは domain に閉じる。
 */
class DigestSelectionPolicy(
    private val popularFeeds: Set<String>,
) {

    fun select(candidates: List<RssItem>, limit: Int, alreadyPosted: Set<String>): List<RssItem> =
        candidates
            .filterNot { it.guid in alreadyPosted }
            .sortedWith(
                compareByDescending<RssItem> { it.feedName in popularFeeds }
                    .thenByDescending { effectiveInstant(it) },
            )
            .take(limit)

    /** タイブレーク用の実効時刻。publishedAt が無い item は fetchedAt を用いる。 */
    private fun effectiveInstant(item: RssItem): Instant = item.publishedAt ?: item.fetchedAt
}
