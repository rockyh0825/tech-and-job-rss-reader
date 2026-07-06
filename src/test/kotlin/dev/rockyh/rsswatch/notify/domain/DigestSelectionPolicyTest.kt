package dev.rockyh.rsswatch.notify.domain

import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DigestSelectionPolicyTest {

    private val now: Instant = Instant.parse("2026-07-06T08:00:00Z")

    private fun item(
        guid: String,
        feedName: String = "その他フィード",
        publishedAt: Instant? = now.minus(Duration.ofHours(1)),
        fetchedAt: Instant = now,
    ): RssItem =
        RssItem(
            guid = guid,
            feedName = feedName,
            category = "tech",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = publishedAt,
            fetchedAt = fetchedAt,
            keywords = emptyList(),
        )

    private val popularFeeds = setOf("はてなブックマーク テクノロジー", "Qiita 人気記事", "Hacker News")

    private val policy = DigestSelectionPolicy(popularFeeds)

    @Test
    fun excludes_already_posted_guids() {
        val candidates = listOf(item("a"), item("b"), item("c"))

        val selected = policy.select(candidates, limit = 5, alreadyPosted = setOf("b"))

        assertEquals(listOf("a", "c"), selected.map { it.guid })
    }

    @Test
    fun ranks_popular_feed_items_before_others() {
        val candidates =
            listOf(
                item("plain", feedName = "その他フィード"),
                item("popular", feedName = "Hacker News"),
            )

        val selected = policy.select(candidates, limit = 5, alreadyPosted = emptySet())

        assertEquals(listOf("popular", "plain"), selected.map { it.guid })
    }

    @Test
    fun breaks_ties_by_published_at_newest_first_within_same_popularity() {
        val candidates =
            listOf(
                item("older-popular", feedName = "Qiita 人気記事", publishedAt = now.minus(Duration.ofHours(5))),
                item("newer-popular", feedName = "Hacker News", publishedAt = now.minus(Duration.ofHours(1))),
                item("older-plain", feedName = "その他", publishedAt = now.minus(Duration.ofHours(4))),
                item("newer-plain", feedName = "個人ブログ", publishedAt = now.minus(Duration.ofHours(2))),
            )

        val selected = policy.select(candidates, limit = 5, alreadyPosted = emptySet())

        assertEquals(
            listOf("newer-popular", "older-popular", "newer-plain", "older-plain"),
            selected.map { it.guid },
        )
    }

    @Test
    fun falls_back_to_fetched_at_for_tiebreak_when_published_at_is_null() {
        val candidates =
            listOf(
                item("no-published", publishedAt = null, fetchedAt = now.minus(Duration.ofHours(1))),
                item("older-published", publishedAt = now.minus(Duration.ofHours(3)), fetchedAt = now),
            )

        val selected = policy.select(candidates, limit = 5, alreadyPosted = emptySet())

        assertEquals(listOf("no-published", "older-published"), selected.map { it.guid })
    }

    @Test
    fun caps_result_at_limit() {
        val candidates = (1..10).map { item("item-$it") }

        val selected = policy.select(candidates, limit = 3, alreadyPosted = emptySet())

        assertEquals(3, selected.size)
    }

    @Test
    fun returns_empty_list_when_no_candidates() {
        val selected = policy.select(emptyList(), limit = 5, alreadyPosted = emptySet())

        assertEquals(emptyList(), selected.map { it.guid })
    }

    @Test
    fun returns_empty_list_when_all_candidates_already_posted() {
        val candidates = listOf(item("a"), item("b"))

        val selected = policy.select(candidates, limit = 5, alreadyPosted = setOf("a", "b"))

        assertEquals(emptyList(), selected.map { it.guid })
    }
}
