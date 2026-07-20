package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.CncfMatchPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.notify.domain.CncfDigestEntry
import dev.rockyh.rsswatch.notify.domain.CncfDigestPublisher
import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BuildCncfDigestUseCaseTest {

    private class FakeArchive(var items: List<RssItem> = emptyList()) : ArchiveQueryPort {
        val receivedCategories = mutableListOf<ItemCategory>()
        val receivedDays = mutableListOf<Int>()

        override fun techRanking(days: Int): List<TechMention> = error("not used by the CNCF digest")

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> {
            receivedCategories += category
            receivedDays += days
            return items
        }

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            error("not used by the CNCF digest")
    }

    /** 実辞書の代わり: テストで使う代表 3 プロジェクトだけを、成熟度の低い順で返す。 */
    private class FakeCncfMatch : CncfMatchPort {
        override fun match(text: String): List<CncfMention> =
            buildList {
                if ("WasmEdge" in text) add(CncfMention("WasmEdge", CncfMaturity.SANDBOX))
                if ("Kubernetes" in text) add(CncfMention("Kubernetes", CncfMaturity.GRADUATED))
                if ("Prometheus" in text) add(CncfMention("Prometheus", CncfMaturity.GRADUATED))
            }
    }

    private class FakeSummarizer(var result: Result<String> = Result.success("要約")) : Summarizer {
        override fun summarize(title: String, summary: String): Result<String> = result
    }

    private class FakeThumbnailResolver(var url: String? = "https://example.com/ogp.png") : ThumbnailResolver {
        override fun resolve(articleUrl: String): String? = url
    }

    private class FakePostedGuidStore(initial: Set<String> = emptySet()) : PostedGuidStore {
        val posted = initial.toMutableSet()
        val marked = mutableListOf<List<String>>()

        override fun postedGuids(since: Instant): Set<String> = posted

        override fun markPosted(guids: List<String>) {
            marked += guids
            posted += guids
        }
    }

    private class FakePublisher(
        var outcome: (List<CncfDigestEntry>) -> PostOutcome = { PostOutcome(it.map { entry -> entry.article.guid }) },
    ) : CncfDigestPublisher {
        val received = mutableListOf<List<CncfDigestEntry>>()

        override fun post(entries: List<CncfDigestEntry>): PostOutcome {
            received += entries
            return outcome(entries)
        }
    }

    private val archive = FakeArchive()
    private val summarizer = FakeSummarizer()
    private val thumbnailResolver = FakeThumbnailResolver()
    private val postedGuidStore = FakePostedGuidStore()
    private val publisher = FakePublisher()

    private fun useCase(maxArticles: Int = 8, windowDays: Int = 7): BuildCncfDigestUseCase =
        BuildCncfDigestUseCase(
            archiveQueryPort = archive,
            cncfMatchPort = FakeCncfMatch(),
            summarizer = summarizer,
            webhookClient = publisher,
            postedGuidRepository = postedGuidStore,
            thumbnailResolver = thumbnailResolver,
            maxArticles = maxArticles,
            windowDays = windowDays,
        )

    private fun item(guid: String, title: String = "title of $guid", publishedAt: String = "2026-07-19T00:00:00Z"): RssItem =
        RssItem(
            guid = guid,
            feedName = "CNCF Blog",
            category = "cncf",
            title = title,
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = Instant.parse(publishedAt),
            fetchedAt = Instant.parse(publishedAt),
            keywords = emptyList(),
        )

    @Test
    fun queries_cncf_category_within_the_window_and_posts_each_article() {
        archive.items = listOf(item("g1"), item("g2"))

        useCase(windowDays = 7).run()

        assertEquals(listOf(ItemCategory.CNCF), archive.receivedCategories)
        assertEquals(listOf(7), archive.receivedDays)
        assertEquals(1, publisher.received.size)
        assertEquals(listOf("g1", "g2"), publisher.received.single().map { it.article.guid })
    }

    @Test
    fun attaches_project_mentions_with_maturity_to_each_entry() {
        archive.items = listOf(item("g1", title = "Kubernetes meets WasmEdge"))

        useCase().run()

        val entry = publisher.received.single().single()
        assertEquals(
            listOf("WasmEdge" to CncfMaturity.SANDBOX, "Kubernetes" to CncfMaturity.GRADUATED),
            entry.mentions.map { it.projectName to it.maturity },
        )
    }

    @Test
    fun orders_articles_mentioning_lower_maturity_projects_first() {
        archive.items =
            listOf(
                item("graduated-only", title = "Prometheus 3.0 release", publishedAt = "2026-07-19T00:00:00Z"),
                item("sandbox-mention", title = "Introducing WasmEdge plugins", publishedAt = "2026-07-17T00:00:00Z"),
            )

        useCase().run()

        assertEquals(
            listOf("sandbox-mention", "graduated-only"),
            publisher.received.single().map { it.article.guid },
        )
    }

    @Test
    fun excludes_already_posted_articles_permanently() {
        archive.items = listOf(item("g1"), item("g2"))
        postedGuidStore.posted += "g1"

        useCase().run()

        assertEquals(listOf("g2"), publisher.received.single().map { it.article.guid })
    }

    @Test
    fun caps_the_number_of_articles_per_post() {
        archive.items = (1..5).map { item("g$it") }

        useCase(maxArticles = 3).run()

        assertEquals(3, publisher.received.single().size)
    }

    @Test
    fun skips_posting_when_there_are_no_candidates() {
        archive.items = emptyList()

        useCase().run()

        assertTrue(publisher.received.isEmpty())
        assertTrue(postedGuidStore.marked.isEmpty())
    }

    @Test
    fun falls_back_to_no_summary_when_summarization_fails() {
        archive.items = listOf(item("g1"))
        summarizer.result = Result.failure(RuntimeException("api down"))

        useCase().run()

        assertNull(publisher.received.single().single().article.summary)
    }

    @Test
    fun falls_back_to_no_thumbnail_when_resolution_fails() {
        archive.items = listOf(item("g1"))
        thumbnailResolver.url = null

        useCase().run()

        assertNull(publisher.received.single().single().article.thumbnailUrl)
    }

    @Test
    fun marks_only_actually_posted_guids() {
        archive.items = listOf(item("g1"), item("g2"))
        publisher.outcome = { PostOutcome(listOf("g1"), RuntimeException("aborted")) }

        useCase().run()

        assertEquals(listOf(listOf("g1")), postedGuidStore.marked)
    }

    @Test
    fun does_not_mark_anything_when_nothing_was_posted() {
        archive.items = listOf(item("g1"))
        publisher.outcome = { PostOutcome(emptyList(), RuntimeException("webhook down")) }

        useCase().run()

        assertTrue(postedGuidStore.marked.isEmpty())
    }
}
