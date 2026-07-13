package dev.rockyh.rsswatch.shared.contract

/**
 * 技術キーワードのカテゴリ。キーワード辞書の各エントリが属する分類の語彙で、
 * ワイヤ上(設定ファイル)では小文字ケバブケースの [value]("cloud-infra" 等)で表記する。
 * カテゴリを追加するときはここに 1 箇所追加する(各 feature に文字列を散らさない)。
 */
enum class TechCategory(val value: String) {
    LANGUAGE("language"),
    FRONTEND("frontend"),
    BACKEND("backend"),
    MOBILE("mobile"),
    DATABASE_MIDDLEWARE("database-middleware"),
    CLOUD_INFRA("cloud-infra"),
    ML_AI("ml-ai"),
    ;

    companion object {
        fun from(value: String): TechCategory =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException(
                    "invalid tech category: \"$value\" (must be one of ${entries.map { it.value }})",
                )
    }
}
