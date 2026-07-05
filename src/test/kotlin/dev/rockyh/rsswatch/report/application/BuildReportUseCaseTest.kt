package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
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
        val receivedKeywordCategories = mutableListOf<String>()

        override fun techRanking(days: Int): List<TechMention> {
            receivedDays += days
            return ranking
        }

        override fun itemsByKeyword(keyword: String, category: String, days: Int): List<RssItem> {
            receivedDays += days
            receivedKeywordCategories += category
            return itemsByKeyword.getOrDefault(keyword, emptyList())
        }

        override fun itemsByCategory(category: String, days: Int): List<RssItem> {
            receivedDays += days
            return itemsByCategory.getOrDefault(category, emptyList())
        }
    }

    @Test
    fun builds_cross_sections_in_job_mention_ranking_order() {
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
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("Kotlin", "Go"), report.crossSections.map { it.keyword })
        assertEquals(listOf(3, 1), report.crossSections.map { it.mentionCount })
        assertEquals(listOf("kotlin-article"), report.crossSections[0].articles.map { it.guid })
    }

    @Test
    fun requests_only_tech_category_articles_for_cross_sections() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 2), TechMention("Go", 1)),
                itemsByKeyword = mapOf("Kotlin" to listOf(rssItem("article", category = "tech"))),
            )
        val useCase = BuildReportUseCase(archive)

        val report = useCase.build(days = 7)

        assertEquals(listOf("tech", "tech"), archive.receivedKeywordCategories)
        assertEquals(listOf("article"), report.crossSections.first().articles.map { it.guid })
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
            )

        val report = useCase.build(days = 7)

        assertEquals(listOf("article-1", "article-2"), report.techArticles.map { it.guid })
        assertEquals(listOf("job-1"), report.jobPostings.map { it.guid })
    }

    @Test
    fun returns_empty_report_when_archive_is_empty() {
        val useCase = BuildReportUseCase(FakeArchive())

        val report = useCase.build(days = 7)

        assertEquals(emptyList(), report.crossSections)
        assertEquals(emptyList(), report.techArticles)
        assertEquals(emptyList(), report.jobPostings)
    }

    @Test
    fun passes_days_to_every_archive_query() {
        val archive = FakeArchive(ranking = listOf(TechMention("Kotlin", 1)))
        val useCase = BuildReportUseCase(archive)

        useCase.build(days = 30)

        assertEquals(setOf(30), archive.receivedDays.toSet())
        // techRanking + itemsByKeyword(Kotlin) + itemsByCategory(tech, jobs)
        assertEquals(4, archive.receivedDays.size)
    }
}
