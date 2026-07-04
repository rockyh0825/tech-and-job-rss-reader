package dev.rockyh.rsswatch.fetch.domain

/** 収集対象フィードの定義(feeds.toml の 1 エントリ)。 */
data class FeedDefinition(
    val name: String,
    val url: String,
    val category: FeedCategory,
)

/** フィードのカテゴリ。feeds.toml では小文字の値("tech" | "jobs")で表記する。 */
enum class FeedCategory(val value: String) {
    TECH("tech"),
    JOBS("jobs"),
    ;

    companion object {
        fun from(value: String): FeedCategory =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException(
                    "invalid category: \"$value\" (must be one of ${entries.map { it.value }})",
                )
    }
}
