package dev.rockyh.rsswatch.notify

import dev.rockyh.rsswatch.capabilities.KeywordCatalogPort
import dev.rockyh.rsswatch.notify.domain.NotifyInterests
import dev.rockyh.rsswatch.shared.contract.TechCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotifyInterestsConfigTest {

    private class FakeKeywordCatalog : KeywordCatalogPort {
        private val byCategory =
            mapOf(
                TechCategory.LANGUAGE to setOf("Kotlin", "Go", "Python"),
                TechCategory.CLOUD_INFRA to setOf("AWS", "Kubernetes", "Terraform"),
            )

        override fun keywordsIn(category: TechCategory): Set<String> = byCategory[category].orEmpty()

        override fun allKeywords(): Set<String> = byCategory.values.flatten().toSet()
    }

    private val config = NotifyInterestsConfig()

    private fun resolve(categories: List<String> = emptyList(), keywords: List<String> = emptyList()): NotifyInterests =
        config.notifyInterests(NotifyInterestsProperties(categories, keywords), FakeKeywordCatalog())

    @Test
    fun resolves_union_of_category_keywords_and_individual_keywords() {
        val interests = resolve(categories = listOf("cloud-infra"), keywords = listOf("Kotlin"))

        assertEquals(setOf("AWS", "Kubernetes", "Terraform", "Kotlin"), interests.keywords)
    }

    @Test
    fun resolves_keyword_names_case_insensitively_to_normalized_names() {
        val interests = resolve(keywords = listOf("kotlin", "aws"))

        assertEquals(setOf("Kotlin", "AWS"), interests.keywords)
    }

    @Test
    fun fails_startup_for_unknown_keyword_with_valid_names_listed() {
        val exception = assertThrows<IllegalArgumentException> { resolve(keywords = listOf("Cobol")) }

        assertTrue("Cobol" in exception.message.orEmpty(), "message should contain the invalid keyword")
        assertTrue("Kotlin" in exception.message.orEmpty(), "message should list valid keywords")
    }

    @Test
    fun fails_startup_for_unknown_category_with_valid_names_listed() {
        val exception = assertThrows<IllegalArgumentException> { resolve(categories = listOf("sre")) }

        assertTrue("sre" in exception.message.orEmpty(), "message should contain the invalid category")
        assertTrue("cloud-infra" in exception.message.orEmpty(), "message should list valid categories")
    }

    @Test
    fun returns_empty_interests_when_nothing_configured() {
        val interests = resolve()

        assertEquals(emptySet(), interests.keywords)
        assertTrue(!interests.isInterested("Kotlin"))
    }
}
