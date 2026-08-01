package dev.rockyh.rsswatch.notify.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class DigestSelectionPolicyTest {

    private val now: Instant = Instant.parse("2026-07-15T08:00:00Z")

    private val policy = DigestSelectionPolicy(cooldownDays = 3)

    private fun candidate(
        keyword: String,
        mentionCount: Int = 0,
        interested: Boolean = false,
        lastFeaturedAt: Instant? = null,
    ) = TechCandidate(keyword, mentionCount, interested, lastFeaturedAt)

    private fun featuredDaysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    @Test
    fun orders_interested_techs_before_others() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30),
                candidate("Kotlin", mentionCount = 2, interested = true),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Kotlin", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun defers_tech_featured_within_cooldown_behind_unfeatured_ones() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30, lastFeaturedAt = featuredDaysAgo(1)),
                candidate("Ruby", mentionCount = 1),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Ruby", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun prefers_higher_mention_tech_once_its_cooldown_has_passed() {
        // 旧仕様(未紹介 → 紹介が古い順)では Ruby が先だった。クールダウンが明けた技術は
        // 言及数で競うため、注目技術が全技術の一巡を待たずに戻ってくる
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30, lastFeaturedAt = featuredDaysAgo(4)),
                candidate("Ruby", mentionCount = 1),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Python", "Ruby"), prioritized.map { it.keyword })
    }

    @Test
    fun treats_tech_featured_exactly_cooldown_days_ago_as_cooled_down() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30, lastFeaturedAt = featuredDaysAgo(3)),
                candidate("Ruby", mentionCount = 1),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Python", "Ruby"), prioritized.map { it.keyword })
    }

    @Test
    fun breaks_tie_by_mention_count_within_the_cooldown_group_not_by_featured_age() {
        // クールダウン内の技術同士は「紹介が古い順」ではなく言及数で並ぶ
        val candidates =
            listOf(
                candidate("Ruby", mentionCount = 1, lastFeaturedAt = featuredDaysAgo(2)),
                candidate("Python", mentionCount = 30, lastFeaturedAt = featuredDaysAgo(1)),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Python", "Ruby"), prioritized.map { it.keyword })
    }

    @Test
    fun breaks_tie_by_mention_count_desc_then_keyword_asc() {
        val candidates =
            listOf(
                candidate("TypeScript", mentionCount = 5),
                candidate("Python", mentionCount = 30),
                candidate("Ruby", mentionCount = 5),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Python", "Ruby", "TypeScript"), prioritized.map { it.keyword })
    }

    @Test
    fun applies_cooldown_within_interested_group_too() {
        val candidates =
            listOf(
                candidate("AWS", mentionCount = 12, interested = true, lastFeaturedAt = featuredDaysAgo(1)),
                candidate("Terraform", mentionCount = 3, interested = true),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Terraform", "AWS"), prioritized.map { it.keyword })
    }

    @Test
    fun keeps_zero_mention_interested_ahead_of_non_interested() {
        val candidates =
            listOf(
                candidate("Python", mentionCount = 30),
                candidate("Elixir", mentionCount = 0, interested = true),
            )

        val prioritized = policy.prioritize(candidates, now)

        assertEquals(listOf("Elixir", "Python"), prioritized.map { it.keyword })
    }

    @Test
    fun rejects_non_positive_cooldown_days() {
        assertFailsWith<IllegalArgumentException> { DigestSelectionPolicy(cooldownDays = 0) }
    }
}
