package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.capabilities.KeywordCatalogPort
import dev.rockyh.rsswatch.keywords.domain.Keywords
import dev.rockyh.rsswatch.shared.contract.TechCategory
import org.springframework.stereotype.Component

/** [KeywordCatalogPort] の実装。domain の [Keywords] 辞書を参照する。 */
@Component
class KeywordCatalogPortImpl : KeywordCatalogPort {

    private val byCategory: Map<TechCategory, Set<String>> =
        Keywords.entries
            .groupBy({ it.category }, { it.normalizedName })
            .mapValues { (_, names) -> names.toSet() }

    // allKeywords は byCategory から導出し、2 つの API の導出元を単一に保つ
    private val allKeywords: Set<String> = byCategory.values.flatten().toSet()

    override fun keywordsIn(category: TechCategory): Set<String> = byCategory[category].orEmpty()

    override fun allKeywords(): Set<String> = allKeywords
}
