package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class BuildReportUseCaseTest {

    private fun rssItem(guid: String, category: String = "tech", keywords: List<String> = emptyList()): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = category,
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = Instant.parse("2026-07-01T00:00:00Z"),
            fetchedAt = Instant.parse("2026-07-01T00:00:00Z"),
            keywords = keywords,
        )

    private class FakeArchive(
        private val ranking: List<TechMention> = emptyList(),
        private val itemsByKeyword: Map<String, List<RssItem>> = emptyMap(),
        private val itemsByCategory: Map<String, List<RssItem>> = emptyMap(),
    ) : ArchiveQueryPort {
        val receivedDays = mutableListOf<Int>()
        var itemsByKeywordCallCount = 0
        val itemsByKeywordsCalls = mutableListOf<List<String>>()
        val techRankingCategories = mutableListOf<ItemCategory>()

        override fun techRanking(category: ItemCategory, days: Int): List<TechMention> {
            receivedDays += days
            techRankingCategories += category
            return ranking
        }

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> {
            receivedDays += days
            itemsByKeywordCallCount++
            return itemsByKeyword.getOrDefault(keyword, emptyList()).filter { it.category == category.value }
        }

        override fun itemsByKeywords(
            keywords: List<String>,
            category: ItemCategory,
            days: Int,
        ): Map<String, List<RssItem>> {
            receivedDays += days
            itemsByKeywordsCalls += keywords
            return keywords.associateWith { keyword ->
                itemsByKeyword.getOrDefault(keyword, emptyList()).filter { it.category == category.value }
            }
        }

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> {
            receivedDays += days
            return itemsByCategory.getOrDefault(category.value, emptyList())
        }
    }

    private class FakePostedGuids(private val posted: Set<String> = emptySet()) : PostedGuidQueryPort {
        override fun postedIn(guids: Set<String>): Set<String> = posted intersect guids
    }

    @Test
    fun builds_cross_sections_in_article_mention_ranking_order() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(
                    ranking = listOf(TechMention("Kotlin", 3), TechMention("Go", 1)),
                    itemsByKeyword =
                        mapOf(
                            "Kotlin" to listOf(rssItem("kotlin-article")),
                            "Go" to listOf(rssItem("go-article")),
                        ),
                ),
                FakePostedGuids(),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("Kotlin", "Go"), report.crossSections.map { it.keyword })
        assertEquals(listOf(3, 1), report.crossSections.map { it.mentionCount })
        assertEquals(listOf("kotlin-article"), report.crossSections[0].articles.map { it.guid })
    }

    @Test
    fun ranks_techs_by_tech_article_mentions_not_by_job_mentions() {
        val archive = FakeArchive(ranking = listOf(TechMention("Kotlin", 3)))
        val useCase = BuildReportUseCase(archive, FakePostedGuids())

        useCase.build(days = 7)

        assertEquals(listOf(ItemCategory.TECH), archive.techRankingCategories)
    }

    @Test
    fun cross_section_articles_contain_only_tech_articles() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(
                    ranking = listOf(TechMention("Kotlin", 2)),
                    itemsByKeyword =
                        mapOf(
                            "Kotlin" to
                                listOf(
                                    rssItem("article", category = "tech"),
                                    rssItem("job-posting", category = "jobs"),
                                ),
                        ),
                ),
                FakePostedGuids(),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("article"), report.crossSections.single().articles.map { it.guid })
    }

    @Test
    fun returns_tech_articles_and_job_postings_lists() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(
                    itemsByCategory =
                        mapOf(
                            "tech" to listOf(rssItem("article-1"), rssItem("article-2")),
                            "jobs" to listOf(rssItem("job-1", category = "jobs")),
                        ),
                ),
                FakePostedGuids(),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("article-1", "article-2"), report.techArticles.map { it.guid })
        assertEquals(listOf("job-1"), report.jobPostings.map { it.guid })
    }

    @Test
    fun returns_empty_report_when_archive_is_empty() {
        val useCase = BuildReportUseCase(FakeArchive(), FakePostedGuids())

        val report = useCase.build(days = 7)

        assertEquals(emptyList(), report.crossSections)
        assertEquals(emptyList(), report.techArticles)
        assertEquals(emptyList(), report.jobPostings)
    }

    @Test
    fun passes_days_to_every_archive_query() {
        val archive = FakeArchive(ranking = listOf(TechMention("Kotlin", 1)))
        val useCase = BuildReportUseCase(archive, FakePostedGuids())

        useCase.build(days = 30)

        assertEquals(setOf(30), archive.receivedDays.toSet())
        // techRanking + itemsByKeywords(一括) + itemsByCategory(tech, jobs)
        assertEquals(4, archive.receivedDays.size)
    }

    @Test
    fun keeps_ranked_keyword_without_articles_as_cross_section_with_empty_articles() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(
                    ranking = listOf(TechMention("Kotlin", 2), TechMention("COBOL", 1)),
                    itemsByKeyword = mapOf("Kotlin" to listOf(rssItem("kotlin-article"))),
                ),
                FakePostedGuids(),
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("Kotlin", "COBOL"), report.crossSections.map { it.keyword })
        assertEquals(emptyList(), report.crossSections[1].articles)
    }

    @Test
    fun fetches_cross_section_articles_with_a_single_batched_query() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 3), TechMention("Go", 1)),
                itemsByKeyword = mapOf("Kotlin" to listOf(rssItem("kotlin-article"))),
            )
        val useCase = BuildReportUseCase(archive, FakePostedGuids())

        useCase.build(days = 7)

        assertEquals(listOf(listOf("Kotlin", "Go")), archive.itemsByKeywordsCalls)
        assertEquals(0, archive.itemsByKeywordCallCount)
    }

    @Test
    fun posted_guids_contain_only_report_articles_already_posted_to_discord() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(
                    ranking = listOf(TechMention("Kotlin", 1)),
                    itemsByKeyword = mapOf("Kotlin" to listOf(rssItem("kotlin-article"))),
                    itemsByCategory =
                        mapOf(
                            "tech" to listOf(rssItem("article-1"), rssItem("article-2")),
                            "jobs" to listOf(rssItem("job-1", category = "jobs")),
                        ),
                ),
                FakePostedGuids(setOf("kotlin-article", "article-2", "not-in-report")),
            )

        val report = useCase.build(days = 7)

        assertEquals(setOf("kotlin-article", "article-2"), report.postedGuids)
    }

    @Test
    fun posted_guids_are_empty_when_nothing_was_posted() {
        val useCase =
            BuildReportUseCase(
                FakeArchive(itemsByCategory = mapOf("tech" to listOf(rssItem("article-1")))),
                FakePostedGuids(),
            )

        val report = useCase.build(days = 7)

        assertEquals(emptySet(), report.postedGuids)
    }
}
