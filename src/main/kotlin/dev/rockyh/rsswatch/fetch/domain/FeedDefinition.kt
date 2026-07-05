package dev.rockyh.rsswatch.fetch.domain

import dev.rockyh.rsswatch.shared.contract.ItemCategory

/** 収集対象フィードの定義(feeds.toml の 1 エントリ)。 */
data class FeedDefinition(
    val name: String,
    val url: String,
    val category: ItemCategory,
)
