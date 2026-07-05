package dev.rockyh.rsswatch.archive.infrastructure

import dev.hsbrysk.kuery.spring.jdbc.SpringJdbcKueryClient
import dev.rockyh.rsswatch.archive.domain.TechRankingEntry
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class RssItemRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: RssItemRepository

    @BeforeEach
    fun setUp() {
        // Arrange(共通): 一時ファイル SQLite に Flyway マイグレーションを適用する
        val dataSource =
            SQLiteDataSource().apply {
                url = "jdbc:sqlite:${tempDir.resolve("archive-test.db")}"
            }
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        repository = RssItemRepository(SpringJdbcKueryClient.builder().dataSource(dataSource).build())
    }

    private fun rssItem(
        guid: String,
        category: String = "tech",
        title: String = "title of $guid",
        publishedAt: Instant? = Instant.now().minus(Duration.ofHours(1)),
        fetchedAt: Instant = Instant.now(),
        keywords: List<String> = emptyList(),
    ): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed of $guid",
            category = category,
            title = title,
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = publishedAt,
            fetchedAt = fetchedAt,
            keywords = keywords,
        )

    // --- insertIgnore ---

    @Test
    fun insert_ignore_returns_number_of_newly_inserted_items() {
        val items = listOf(rssItem("a"), rssItem("b"))

        val inserted = repository.insertIgnore(items)

        assertEquals(2, inserted)
    }

    @Test
    fun insert_ignore_does_not_duplicate_rows_when_same_guid_is_inserted_twice() {
        val item = rssItem("a", category = "tech")
        repository.insertIgnore(listOf(item))

        val insertedAgain = repository.insertIgnore(listOf(item))

        assertEquals(0, insertedAgain)
        assertEquals(1, repository.itemsByCategory("tech", days = 7).size)
    }

    @Test
    fun insert_ignore_returns_zero_for_empty_list() {
        val inserted = repository.insertIgnore(emptyList())

        assertEquals(0, inserted)
    }

    @Test
    fun insert_ignore_keeps_keywords_idempotent_when_same_item_is_reinserted() {
        val item = rssItem("a", keywords = listOf("Kotlin", "Kafka"))
        repository.insertIgnore(listOf(item))

        repository.insertIgnore(listOf(item))

        val stored = repository.itemsByKeyword("Kotlin", category = "tech", days = 7)
        assertEquals(1, stored.size)
        assertEquals(listOf("Kafka", "Kotlin"), stored.single().keywords)
    }

    @Test
    fun round_trip_preserves_all_fields_with_keywords_sorted_alphabetically() {
        val publishedAt = Instant.now().minus(Duration.ofHours(2))
        val fetchedAt = Instant.now().minusSeconds(30)
        val item =
            RssItem(
                guid = "guid-1",
                feedName = "Zenn",
                category = "tech",
                title = "Kotlin で Kafka",
                url = "https://example.com/1",
                summary = "概要テキスト",
                publishedAt = publishedAt,
                fetchedAt = fetchedAt,
                keywords = listOf("Kotlin", "Kafka"),
            )
        repository.insertIgnore(listOf(item))

        val stored = repository.itemsByCategory("tech", days = 7).single()

        assertEquals(item.copy(keywords = listOf("Kafka", "Kotlin")), stored)
    }

    @Test
    fun round_trip_preserves_null_published_at() {
        repository.insertIgnore(listOf(rssItem("a", publishedAt = null)))

        val stored = repository.itemsByCategory("tech", days = 7).single()

        assertNull(stored.publishedAt)
    }

    // --- techRanking ---

    @Test
    fun tech_ranking_counts_job_keyword_mentions_in_descending_order() {
        repository.insertIgnore(
            listOf(
                rssItem("job1", category = "jobs", keywords = listOf("Kotlin", "AWS")),
                rssItem("job2", category = "jobs", keywords = listOf("Kotlin")),
                rssItem("job3", category = "jobs", keywords = listOf("AWS", "Kotlin")),
                rssItem("job4", category = "jobs", keywords = listOf("Go")),
            ),
        )

        val ranking = repository.techRanking(days = 7)

        assertEquals(
            listOf(
                TechRankingEntry("Kotlin", 3),
                TechRankingEntry("AWS", 2),
                TechRankingEntry("Go", 1),
            ),
            ranking,
        )
    }

    @Test
    fun tech_ranking_ignores_tech_articles() {
        repository.insertIgnore(
            listOf(
                rssItem("article", category = "tech", keywords = listOf("Kotlin")),
                rssItem("job", category = "jobs", keywords = listOf("Go")),
            ),
        )

        val ranking = repository.techRanking(days = 7)

        assertEquals(listOf(TechRankingEntry("Go", 1)), ranking)
    }

    @Test
    fun tech_ranking_excludes_jobs_older_than_days_window() {
        repository.insertIgnore(
            listOf(
                rssItem(
                    "old-job",
                    category = "jobs",
                    publishedAt = Instant.now().minus(Duration.ofDays(10)),
                    keywords = listOf("Kotlin"),
                ),
                rssItem("recent-job", category = "jobs", keywords = listOf("Go")),
            ),
        )

        val ranking = repository.techRanking(days = 7)

        assertEquals(listOf(TechRankingEntry("Go", 1)), ranking)
    }

    @Test
    fun tech_ranking_returns_empty_list_when_no_jobs_exist() {
        val ranking = repository.techRanking(days = 7)

        assertEquals(emptyList(), ranking)
    }

    // --- itemsByCategory ---

    @Test
    fun items_by_category_returns_only_that_category_newest_first() {
        val now = Instant.now()
        repository.insertIgnore(
            listOf(
                rssItem("older", category = "tech", publishedAt = now.minus(Duration.ofHours(3))),
                rssItem("newer", category = "tech", publishedAt = now.minus(Duration.ofHours(1))),
                rssItem("job", category = "jobs", publishedAt = now),
            ),
        )

        val items = repository.itemsByCategory("tech", days = 7)

        assertEquals(listOf("newer", "older"), items.map { it.guid })
    }

    @Test
    fun items_by_category_falls_back_to_fetched_at_when_published_at_is_null() {
        val now = Instant.now()
        repository.insertIgnore(
            listOf(
                rssItem("published-old", publishedAt = now.minus(Duration.ofHours(5))),
                rssItem("no-published", publishedAt = null, fetchedAt = now),
            ),
        )

        val items = repository.itemsByCategory("tech", days = 7)

        assertEquals(listOf("no-published", "published-old"), items.map { it.guid })
    }

    @Test
    fun items_by_category_excludes_items_older_than_days_window() {
        repository.insertIgnore(
            listOf(
                rssItem("old", publishedAt = Instant.now().minus(Duration.ofDays(10))),
                rssItem("recent"),
            ),
        )

        val items = repository.itemsByCategory("tech", days = 7)

        assertEquals(listOf("recent"), items.map { it.guid })
    }

    @Test
    fun items_by_category_returns_empty_list_when_no_items_exist() {
        val items = repository.itemsByCategory("tech", days = 7)

        assertEquals(emptyList(), items)
    }

    // --- itemsByKeyword ---

    @Test
    fun items_by_keyword_returns_only_items_of_the_given_category() {
        repository.insertIgnore(
            listOf(
                rssItem("article", category = "tech", keywords = listOf("Kotlin")),
                rssItem("job", category = "jobs", keywords = listOf("Kotlin")),
            ),
        )

        val items = repository.itemsByKeyword("Kotlin", category = "tech", days = 7)

        assertEquals(listOf("article"), items.map { it.guid })
    }

    @Test
    fun items_by_keyword_returns_only_items_tagged_with_that_keyword() {
        repository.insertIgnore(
            listOf(
                rssItem("kotlin-article", keywords = listOf("Kotlin")),
                rssItem("go-article", keywords = listOf("Go")),
                rssItem("both", keywords = listOf("Kotlin", "Go")),
            ),
        )

        val items = repository.itemsByKeyword("Kotlin", category = "tech", days = 7)

        assertEquals(setOf("kotlin-article", "both"), items.map { it.guid }.toSet())
    }

    @Test
    fun items_by_keyword_excludes_items_older_than_days_window() {
        repository.insertIgnore(
            listOf(
                rssItem(
                    "old",
                    publishedAt = Instant.now().minus(Duration.ofDays(10)),
                    keywords = listOf("Kotlin"),
                ),
                rssItem("recent", keywords = listOf("Kotlin")),
            ),
        )

        val items = repository.itemsByKeyword("Kotlin", category = "tech", days = 7)

        assertEquals(listOf("recent"), items.map { it.guid })
    }

    @Test
    fun items_by_keyword_returns_newest_first() {
        val now = Instant.now()
        repository.insertIgnore(
            listOf(
                rssItem("older", publishedAt = now.minus(Duration.ofHours(3)), keywords = listOf("Kotlin")),
                rssItem("newer", publishedAt = now.minus(Duration.ofHours(1)), keywords = listOf("Kotlin")),
            ),
        )

        val items = repository.itemsByKeyword("Kotlin", category = "tech", days = 7)

        assertEquals(listOf("newer", "older"), items.map { it.guid })
    }
}
