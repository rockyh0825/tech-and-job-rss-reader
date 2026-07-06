package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.notify.domain.DigestEntry
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.DigestSelectionPolicy
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BuildDigestUseCaseTest {

    private val now: Instant = Instant.parse("2026-07-06T08:00:00Z")

    // --- Fakes(この feature の port はすべて interface。repo idiom に合わせ手書きの test double を使う)---

    private class FakeArchive(private val techItems: List<RssItem>) : ArchiveQueryPort {
        override fun techRanking(days: Int): List<TechMention> = emptyList()

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> =
            if (category == ItemCategory.TECH) techItems else emptyList()

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> = emptyList()
    }

    private class FakeSummarizer(private val result: Result<String>) : Summarizer {
        val receivedTitles = mutableListOf<String>()

        override fun summarize(title: String, summary: String): Result<String> {
            receivedTitles += title
            return result
        }
    }

    private class FakePublisher(private val result: Result<Unit>) : DigestPublisher {
        var posted: List<DigestEntry>? = null

        override fun post(entries: List<DigestEntry>): Result<Unit> {
            posted = entries
            return result
        }
    }

    private class FakePostedGuidStore(private val alreadyPosted: Set<String> = emptySet()) : PostedGuidStore {
        var marked: List<String>? = null

        override fun postedGuids(since: Instant): Set<String> = alreadyPosted

        override fun markPosted(guids: List<String>) {
            marked = guids
        }
    }

    private fun useCase(
        archive: ArchiveQueryPort,
        summarizer: Summarizer = FakeSummarizer(Result.success("要約")),
        publisher: DigestPublisher = FakePublisher(Result.success(Unit)),
        store: PostedGuidStore = FakePostedGuidStore(),
        limit: Int = 5,
    ): BuildDigestUseCase =
        BuildDigestUseCase(
            archiveQueryPort = archive,
            policy = DigestSelectionPolicy(popularFeeds = emptySet()),
            summarizer = summarizer,
            webhookClient = publisher,
            postedGuidRepository = store,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            limit = limit,
            postedLookbackDays = 2,
        )

    private fun item(guid: String, ageHours: Long = 1, keywords: List<String> = listOf("Kotlin")): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = "tech",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = now.minus(Duration.ofHours(ageHours)),
            fetchedAt = now,
            keywords = keywords,
        )

    @Test
    fun posts_selected_items_with_ai_summary_and_marks_them_posted() {
        val publisher = FakePublisher(Result.success(Unit))
        val store = FakePostedGuidStore()
        val useCase = useCase(FakeArchive(listOf(item("a"), item("b", ageHours = 2))), publisher = publisher, store = store)

        useCase.run()

        assertEquals(listOf("要約", "要約"), publisher.posted!!.map { it.summary })
        assertEquals(setOf("a", "b"), store.marked!!.toSet())
    }

    @Test
    fun falls_back_to_no_summary_when_summarization_fails() {
        val publisher = FakePublisher(Result.success(Unit))
        val store = FakePostedGuidStore()
        val useCase =
            useCase(
                FakeArchive(listOf(item("a"))),
                summarizer = FakeSummarizer(Result.failure(RuntimeException("api down"))),
                publisher = publisher,
                store = store,
            )

        useCase.run()

        val entry = publisher.posted!!.single()
        assertNull(entry.summary)
        assertEquals(listOf("Kotlin"), entry.keywords)
        assertEquals(listOf("a"), store.marked)
    }

    @Test
    fun does_not_post_when_no_candidates_are_selected() {
        val publisher = FakePublisher(Result.success(Unit))
        val store = FakePostedGuidStore()
        val useCase = useCase(FakeArchive(emptyList()), publisher = publisher, store = store)

        useCase.run()

        assertNull(publisher.posted)
        assertNull(store.marked)
    }

    @Test
    fun does_not_mark_posted_when_webhook_fails() {
        val store = FakePostedGuidStore()
        val useCase =
            useCase(
                FakeArchive(listOf(item("a"))),
                publisher = FakePublisher(Result.failure(RuntimeException("discord down"))),
                store = store,
            )

        useCase.run()

        assertNull(store.marked)
    }

    @Test
    fun excludes_already_posted_guids_and_caps_at_limit() {
        val candidates = (1..10).map { item("g$it", ageHours = it.toLong()) }
        val publisher = FakePublisher(Result.success(Unit))
        val useCase =
            useCase(
                FakeArchive(candidates),
                publisher = publisher,
                store = FakePostedGuidStore(alreadyPosted = setOf("g1")),
                limit = 3,
            )

        useCase.run()

        val postedGuids = publisher.posted!!.map { it.url.substringAfterLast('/') }
        assertEquals(3, postedGuids.size)
        assertFalse(postedGuids.contains("g1"))
        // 除外後に残る中で publishedAt が新しい(ageHours が小さい)順に上位 3 件
        assertTrue(postedGuids.containsAll(listOf("g2", "g3", "g4")))
    }
}
