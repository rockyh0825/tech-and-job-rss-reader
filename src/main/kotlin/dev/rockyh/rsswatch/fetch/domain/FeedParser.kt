package dev.rockyh.rsswatch.fetch.domain

import java.time.Instant

/** フィード 1 本を取得・パースするアダプタの抽象(実装: fetch/infrastructure の RomeFeedParser)。 */
interface FeedParser {

    /** フィードを取得してエントリ一覧を返す。取得・パース失敗は例外を投げる。 */
    fun parse(feed: FeedDefinition): List<ParsedEntry>
}

/** パース済みの RSS エントリ(キーワード抽出前)。 */
data class ParsedEntry(
    val guid: String,
    val title: String,
    val url: String,
    val summary: String,
    val publishedAt: Instant?,
)
