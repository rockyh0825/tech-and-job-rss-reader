package dev.rockyh.rsswatch.shared.contract

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RssItemTest {

    private val item =
        RssItem(
            guid = "https://example.com/articles/1",
            feedName = "Zenn",
            category = "tech",
            title = "Kotlin で Kafka Streams に入門する",
            url = "https://example.com/articles/1",
            summary = "Kotlin と Spring Boot で Kafka を使う話",
            publishedAt = Instant.parse("2026-07-01T09:30:00Z"),
            fetchedAt = Instant.parse("2026-07-01T10:00:00Z"),
            keywords = listOf("Kotlin", "Kafka", "Spring Boot"),
        )

    @Test
    fun json_round_trip_preserves_all_fields() {
        val json = item.toJson()

        val restored = RssItem.fromJson(json)

        assertEquals(item, restored)
    }

    @Test
    fun json_round_trip_preserves_null_published_at() {
        val withoutPublishedAt = item.copy(publishedAt = null)

        val restored = RssItem.fromJson(withoutPublishedAt.toJson())

        assertEquals(withoutPublishedAt, restored)
        assertNull(restored.publishedAt)
    }

    @Test
    fun serializes_with_stable_contract_field_names() {
        val json = item.toJson()

        val tree = ObjectMapper().readTree(json)

        val expectedFieldNames =
            setOf(
                "guid",
                "feedName",
                "category",
                "title",
                "url",
                "summary",
                "publishedAt",
                "fetchedAt",
                "keywords",
            )
        assertEquals(expectedFieldNames, tree.fieldNames().asSequence().toSet())
    }

    @Test
    fun serializes_instants_as_iso8601_strings() {
        val json = item.toJson()

        val tree = ObjectMapper().readTree(json)

        assertEquals("2026-07-01T09:30:00Z", tree["publishedAt"].asText())
        assertEquals("2026-07-01T10:00:00Z", tree["fetchedAt"].asText())
    }

    @Test
    fun deserialization_ignores_unknown_fields_for_backward_compatible_additions() {
        val jsonWithExtraField =
            item.toJson().removeSuffix("}") + ""","addedInFutureVersion":"value"}"""

        val restored = RssItem.fromJson(jsonWithExtraField)

        assertEquals(item, restored)
    }
}
