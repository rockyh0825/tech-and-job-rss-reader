package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.CncfMatchPort
import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BuildCncfReportUseCaseTest {

    private class FakeArchive(var items: List<RssItem> = emptyList()) : ArchiveQueryPort {
        val receivedCategories = mutableListOf<ItemCategory>()
        val receivedDays = mutableListOf<Int>()

        override fun techRanking(days: Int): List<TechMention> = error("not used by the CNCF report")

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> {
            receivedCategories += category
            receivedDays += days
            return items
        }

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            error("not used by the CNCF report")
    }

    /** 実辞書の代わり: テストで使う代表 3 プロジェクトだけを、成熟度の低い順で返す。 */
    private class FakeCncfMatch : CncfMatchPort {
        override fun match(text: String): List<CncfMention> =
            buildList {
                if ("WasmEdge" in text) add(CncfMention("WasmEdge", CncfMaturity.SANDBOX))
                if ("Knative" in text) add(CncfMention("Knative", CncfMaturity.INCUBATING))
                if ("Kubernetes" in text) add(CncfMention("Kubernetes", CncfMaturity.GRADUATED))
            }
    }

    private class FakePostedGuids(var posted: Set<String> = emptySet()) : PostedGuidQueryPort {
        override fun postedGuids(): Set<String> = posted
    }

    private val archive = FakeArchive()

    private val postedGuids = FakePostedGuids()

    private val useCase = BuildCncfReportUseCase(archive, FakeCncfMatch(), postedGuids)

    private fun item(guid: String, title: String = "title of $guid", publishedAt: String? = "2026-07-19T00:00:00Z"): RssItem =
        RssItem(
            guid = guid,
            feedName = "CNCF Blog",
            category = "cncf",
            title = title,
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = publishedAt?.let(Instant::parse),
            fetchedAt = Instant.parse("2026-07-19T00:00:00Z"),
            keywords = emptyList(),
        )

    @Test
    fun queries_cncf_category_within_the_requested_window() {
        archive.items = listOf(item("g1"))

        useCase.build(days = 14)

        assertEquals(listOf(ItemCategory.CNCF), archive.receivedCategories)
        assertEquals(listOf(14), archive.receivedDays)
    }

    @Test
    fun attaches_project_mentions_from_title_and_summary_to_each_article() {
        archive.items = listOf(item("g1", title = "Kubernetes meets WasmEdge"))

        val report = useCase.build(days = 7)

        val article = report.articles.single()
        assertEquals("g1", article.item.guid)
        assertEquals(
            listOf(
                CncfMention("WasmEdge", CncfMaturity.SANDBOX),
                CncfMention("Kubernetes", CncfMaturity.GRADUATED),
            ),
            article.mentions,
        )
    }

    @Test
    fun orders_articles_mentioning_lower_maturity_projects_first() {
        archive.items =
            listOf(
                item("graduated", title = "Kubernetes release", publishedAt = "2026-07-19T00:00:00Z"),
                item("incubating", title = "Knative eventing", publishedAt = "2026-07-18T00:00:00Z"),
                item("sandbox", title = "Introducing WasmEdge", publishedAt = "2026-07-17T00:00:00Z"),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("sandbox", "incubating", "graduated"), report.articles.map { it.item.guid })
    }

    @Test
    fun orders_newer_articles_first_within_the_same_tier() {
        archive.items =
            listOf(
                item("older", title = "Kubernetes 1.34", publishedAt = "2026-07-17T00:00:00Z"),
                item("newer", title = "Kubernetes 1.35", publishedAt = "2026-07-19T00:00:00Z"),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("newer", "older"), report.articles.map { it.item.guid })
    }

    @Test
    fun places_articles_without_mentions_after_articles_with_mentions() {
        archive.items =
            listOf(
                item("no-mention", title = "community update", publishedAt = "2026-07-19T00:00:00Z"),
                item("graduated", title = "Kubernetes release", publishedAt = "2026-07-17T00:00:00Z"),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("graduated", "no-mention"), report.articles.map { it.item.guid })
    }

    @Test
    fun places_articles_with_null_published_at_last_within_the_same_tier() {
        archive.items =
            listOf(
                item("undated", title = "Kubernetes undated", publishedAt = null),
                item("dated", title = "Kubernetes dated", publishedAt = "2026-07-17T00:00:00Z"),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("dated", "undated"), report.articles.map { it.item.guid })
    }

    @Test
    fun breaks_full_ties_deterministically_by_guid() {
        archive.items =
            listOf(
                item("b", title = "Kubernetes b", publishedAt = "2026-07-19T00:00:00Z"),
                item("a", title = "Kubernetes a", publishedAt = "2026-07-19T00:00:00Z"),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("a", "b"), report.articles.map { it.item.guid })
    }

    @Test
    fun returns_empty_report_when_no_articles_exist() {
        archive.items = emptyList()

        val report = useCase.build(days = 7)

        assertTrue(report.articles.isEmpty())
    }

    @Test
    fun posted_guids_contain_only_report_articles_already_posted_to_discord() {
        archive.items = listOf(item("posted-article"), item("fresh-article"))
        postedGuids.posted = setOf("posted-article", "not-in-report")

        val report = useCase.build(days = 7)

        assertEquals(setOf("posted-article"), report.postedGuids)
    }

    @Test
    fun posted_guids_are_empty_when_nothing_was_posted() {
        archive.items = listOf(item("fresh-article"))

        val report = useCase.build(days = 7)

        assertEquals(emptySet(), report.postedGuids)
    }
}
