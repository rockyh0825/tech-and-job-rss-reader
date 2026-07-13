package dev.rockyh.rsswatch.notify.domain

import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DigestSelectionPolicyTest {

    private val policy = DigestSelectionPolicy()

    private fun candidate(
        keyword: String,
        mentionCount: Int = 0,
        interested: Boolean = false,
        lastFeaturedAt: Instant? = null,
    ) = TechCandidate(keyword, mentionCount, interested, lastFeaturedAt)

    @Test
    fun orders_interested_techs_before_others() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30),
                candidate("Kotlin", mentionCount = 2, interested = true),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Kotlin", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun orders_never_featured_before_previously_featured() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30, lastFeaturedAt = Instant.parse("2026-07-12T08:00:00Z")),
                candidate("Ruby", mentionCount = 1),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Ruby", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun orders_older_featured_before_recently_featured() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30, lastFeaturedAt = Instant.parse("2026-07-12T08:00:00Z")),
                candidate("Ruby", mentionCount = 1, lastFeaturedAt = Instant.parse("2026-07-01T08:00:00Z")),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Ruby", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun breaks_tie_by_mention_count_desc_then_keyword_asc() {
        val candidates =
            listOf(
                candidate("TypeScript", mentionCount = 5),
                candidate("Python", mentionCount = 30),
                candidate("Ruby", mentionCount = 5),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Python", "Ruby", "TypeScript"), prioritized.map { it.keyword })
    }

    @Test
    fun applies_rotation_within_interested_group_too() {
        val candidates =
            listOf(
                candidate("AWS", mentionCount = 12, interested = true, lastFeaturedAt = Instant.parse("2026-07-12T08:00:00Z")),
                candidate("Terraform", mentionCount = 3, interested = true),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Terraform", "AWS"), prioritized.map { it.keyword })
    }

    @Test
    fun keeps_zero_mention_interested_ahead_of_non_interested() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30),
                candidate("Elixir", mentionCount = 0, interested = true),
            )

        val prioritized = policy.prioritize(candidates)

        assertEquals(listOf("Elixir", "Python"), prioritized.map { it.keyword })
    }
}
