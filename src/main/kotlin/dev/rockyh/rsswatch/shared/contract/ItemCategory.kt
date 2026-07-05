package dev.rockyh.rsswatch.shared.contract

/**
 * item のカテゴリ。[RssItem.category] と feeds.toml の category が取り得る値の語彙で、
 * ワイヤ上(JSON・DB)では小文字の [value]("tech" | "jobs")で表記する。
 * カテゴリを追加するときはここに 1 箇所追加する(各 feature に文字列を散らさない)。
 */
enum class ItemCategory(val value: String) {
    TECH("tech"),
    JOBS("jobs"),
    ;

    companion object {
        fun from(value: String): ItemCategory =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException(
                    "invalid category: \"$value\" (must be one of ${entries.map { it.value }})",
                )
    }
}
