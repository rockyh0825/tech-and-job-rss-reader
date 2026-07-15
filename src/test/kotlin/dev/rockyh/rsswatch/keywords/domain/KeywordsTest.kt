package dev.rockyh.rsswatch.keywords.domain

import dev.rockyh.rsswatch.shared.contract.TechCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KeywordsTest {

    @Test
    fun every_category_has_at_least_one_entry() {
        val usedCategories = Keywords.entries.map { it.category }.toSet()

        assertEquals(TechCategory.entries.toSet(), usedCategories)
    }

    @ParameterizedTest(name = "{0} は {1}")
    @CsvSource(
        "Kotlin, LANGUAGE",
        "Go, LANGUAGE",
        "React, FRONTEND",
        "Spring Boot, BACKEND",
        "iOS, MOBILE",
        "Kafka, DATABASE_MIDDLEWARE",
        "AWS, CLOUD_INFRA",
        "Kubernetes, CLOUD_INFRA",
        "Terraform, CLOUD_INFRA",
        "LLM, ML_AI",
    )
    fun assigns_expected_category_to_representative_keywords(name: String, expected: TechCategory) {
        val entry = Keywords.entries.single { it.normalizedName == name }

        assertEquals(expected, entry.category)
    }

    @Test
    fun normalized_names_are_unique() {
        val names = Keywords.entries.map { it.normalizedName }

        assertTrue(names.size == names.toSet().size, "normalized names must not duplicate")
    }
}
