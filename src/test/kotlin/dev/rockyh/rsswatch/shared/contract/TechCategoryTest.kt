package dev.rockyh.rsswatch.shared.contract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TechCategoryTest {

    @Test
    fun from_resolves_config_value_to_category() {
        assertEquals(TechCategory.LANGUAGE, TechCategory.from("language"))
        assertEquals(TechCategory.CLOUD_INFRA, TechCategory.from("cloud-infra"))
        assertEquals(TechCategory.DATABASE_MIDDLEWARE, TechCategory.from("database-middleware"))
        assertEquals(TechCategory.ML_AI, TechCategory.from("ml-ai"))
    }

    @Test
    fun from_throws_with_valid_values_listed_for_unknown_value() {
        val exception = assertThrows<IllegalArgumentException> { TechCategory.from("sre") }

        assertTrue("sre" in exception.message.orEmpty(), "message should contain the invalid value")
        assertTrue("cloud-infra" in exception.message.orEmpty(), "message should list valid values")
    }
}
