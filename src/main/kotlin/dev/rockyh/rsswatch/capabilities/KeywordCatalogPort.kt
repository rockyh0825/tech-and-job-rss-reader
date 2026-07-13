package dev.rockyh.rsswatch.capabilities

import dev.rockyh.rsswatch.shared.contract.TechCategory

/**
 * 技術キーワード辞書の参照 Port(feature 間境界)。
 *
 * - 依存する側: notify(興味設定のカテゴリ展開とキーワード名バリデーションに使う)
 * - 実装する側: keywords/application(KeywordCatalogPortImpl)
 */
interface KeywordCatalogPort {

    /** [category] に属する技術キーワードの正規化名の集合を返す。 */
    fun keywordsIn(category: TechCategory): Set<String>

    /** 辞書に登録されている全技術キーワードの正規化名の集合を返す。 */
    fun allKeywords(): Set<String>
}
