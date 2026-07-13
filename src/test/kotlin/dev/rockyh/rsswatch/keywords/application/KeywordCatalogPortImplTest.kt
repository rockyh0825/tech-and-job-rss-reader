package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.keywords.domain.Keywords
import dev.rockyh.rsswatch.shared.contract.TechCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class KeywordCatalogPortImplTest {

    private val catalog = KeywordCatalogPortImpl()

    @Test
    fun keywords_in_returns_normalized_names_of_the_category() {
        val cloudInfra = catalog.keywordsIn(TechCategory.CLOUD_INFRA)

        assertTrue("AWS" in cloudInfra, "AWS should be in $cloudInfra")
        assertTrue("Kubernetes" in cloudInfra, "Kubernetes should be in $cloudInfra")
        assertTrue("Kotlin" !in cloudInfra, "Kotlin (LANGUAGE) should not be in cloud-infra")
    }

    @Test
    fun all_keywords_returns_every_normalized_name() {
        val all = catalog.allKeywords()

        assertEquals(Keywords.entries.map { it.normalizedName }.toSet(), all)
    }
}
