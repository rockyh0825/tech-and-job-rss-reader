package dev.rockyh.rsswatch.fetch.domain

/** 巡回対象フィード定義の取得元の抽象(実装: fetch/infrastructure の FeedConfigLoader)。 */
interface FeedConfigSource {

    fun feeds(): List<FeedDefinition>
}
