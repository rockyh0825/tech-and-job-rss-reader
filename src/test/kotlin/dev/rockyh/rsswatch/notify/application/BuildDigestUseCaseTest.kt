package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.notify.domain.TechDigest
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** 技術グループ一式に含まれる記事 guid を投稿順に並べる。 */
private fun List<TechDigest>.allGuids(): List<String> = flatMap { digest -> digest.articles.map { it.guid } }

class BuildDigestUseCaseTest {

    private val now: Instant = Instant.parse("2026-07-06T08:00:00Z")

    // --- Fakes(この feature の port はすべて interface。repo idiom に合わせ手書きの test double を使う)---

    private class FakeArchive(
        private val ranking: List<TechMention>,
        private val articlesByKeyword: Map<String, List<RssItem>> = emptyMap(),
    ) : ArchiveQueryPort {
        override fun techRanking(days: Int): List<TechMention> = ranking

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> = emptyList()

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            if (category == ItemCategory.TECH) articlesByKeyword[keyword].orEmpty() else emptyList()
    }

    private class FakeSummarizer(private val result: Result<String>) : Summarizer {
        override fun summarize(title: String, summary: String): Result<String> = result
    }

    /**
     * 既定は「渡された記事を全部投稿できた」。記事ごとに 1 通ずつ投稿する実装では途中で失敗し得るので、
     * [outcomeFor] を差し替えて「一部だけ投稿できた」「1 件も投稿できなかった」も表現する。
     */
    private class FakePublisher(
        private val outcomeFor: (List<TechDigest>) -> PostOutcome = { PostOutcome(it.allGuids()) },
    ) : DigestPublisher {
        var posted: List<TechDigest>? = null

        override fun post(digests: List<TechDigest>): PostOutcome {
            posted = digests
            return outcomeFor(digests)
        }
    }

    private class FakePostedGuidStore(private val alreadyPosted: Set<String> = emptySet()) : PostedGuidStore {
        var marked: List<String>? = null
        var sinceSeen: Instant? = null

        override fun postedGuids(since: Instant): Set<String> {
            sinceSeen = since
            return alreadyPosted
        }

        override fun markPosted(guids: List<String>) {
            marked = guids
        }
    }

    private fun useCase(
        archive: ArchiveQueryPort,
        summarizer: Summarizer = FakeSummarizer(Result.success("要約")),
        publisher: DigestPublisher = FakePublisher(),
        store: PostedGuidStore = FakePostedGuidStore(),
        techLimit: Int = 3,
        articlesPerTech: Int = 3,
        windowDays: Int = 7,
    ): BuildDigestUseCase =
        BuildDigestUseCase(
            archiveQueryPort = archive,
            summarizer = summarizer,
            webhookClient = publisher,
            postedGuidRepository = store,
            techLimit = techLimit,
            articlesPerTech = articlesPerTech,
            windowDays = windowDays,
        )

    private fun item(guid: String): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = "tech",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = now,
            fetchedAt = now,
            keywords = listOf("Kotlin"),
        )

    private fun TechDigest.articleGuids(): List<String> = articles.map { it.guid }

    @Test
    fun builds_sections_per_top_tech_with_related_articles_and_marks_all_shown() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5), TechMention("Go", 3)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"), item("b")), "Go" to listOf(item("c"))),
            )
        val publisher = FakePublisher()
        val store = FakePostedGuidStore()

        useCase(archive, publisher = publisher, store = store).run()

        val posted = publisher.posted!!
        assertEquals(listOf("Kotlin", "Go"), posted.map { it.keyword })
        assertEquals(listOf(5, 3), posted.map { it.mentionCount })
        assertEquals(listOf("a", "b"), posted[0].articleGuids())
        assertEquals(listOf("c"), posted[1].articleGuids())
        assertEquals(listOf("要約", "要約"), posted[0].articles.map { it.summary })
        assertEquals(setOf("a", "b", "c"), store.marked!!.toSet())
    }

    @Test
    fun falls_back_to_no_summary_when_summarization_fails() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val publisher = FakePublisher()

        useCase(archive, summarizer = FakeSummarizer(Result.failure(RuntimeException("api down"))), publisher = publisher).run()

        assertNull(publisher.posted!!.single().articles.single().summary)
    }

    @Test
    fun does_not_post_when_no_tech_has_articles() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5)),
                articlesByKeyword = emptyMap(), // Kotlin に紐づく tech 記事が無い
            )
        val publisher = FakePublisher()
        val store = FakePostedGuidStore()

        useCase(archive, publisher = publisher, store = store).run()

        assertNull(publisher.posted)
        assertNull(store.marked)
    }

    @Test
    fun does_not_mark_posted_when_nothing_could_be_posted() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val store = FakePostedGuidStore()
        val publisher = FakePublisher { PostOutcome(emptyList(), RuntimeException("discord down")) }

        useCase(archive, publisher = publisher, store = store).run()

        assertNull(store.marked)
    }

    @Test
    fun marks_only_the_articles_that_were_actually_posted_when_posting_stops_midway() {
        // 記事ごとに 1 通ずつ投稿するので、途中で失敗すると一部だけ Discord に出ている状態になる。
        // 出た記事だけを通知済みにする(全件記録すると未投稿の b/c を永久に取りこぼす)
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"), item("b"), item("c"))),
            )
        val store = FakePostedGuidStore()
        val publisher = FakePublisher { PostOutcome(listOf("a"), RuntimeException("discord down")) }

        useCase(archive, publisher = publisher, store = store).run()

        assertEquals(listOf("a"), store.marked)
    }

    @Test
    fun marks_posted_articles_even_when_the_publisher_reports_a_failure_afterwards() {
        // 記事は全部投稿できたがリンクだけ落ちた、というケースでも記事は通知済みにする(翌日の重複を防ぐ)
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"), item("b"))),
            )
        val store = FakePostedGuidStore()
        val publisher = FakePublisher { PostOutcome(it.allGuids(), RuntimeException("cta down")) }

        useCase(archive, publisher = publisher, store = store).run()

        assertEquals(listOf("a", "b"), store.marked)
    }

    @Test
    fun excludes_already_posted_articles_permanently_by_querying_from_epoch() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("posted"), item("fresh"))),
            )
        val publisher = FakePublisher()
        val store = FakePostedGuidStore(alreadyPosted = setOf("posted"))

        useCase(archive, publisher = publisher, store = store).run()

        assertEquals(listOf("fresh"), publisher.posted!!.single().articleGuids())
        // 通知済み全件を除外するため EPOCH 起点で照会する(窓で絞らない=永続的な重複排除)
        assertEquals(Instant.EPOCH, store.sinceSeen)
    }

    @Test
    fun caps_techs_and_articles_per_tech_at_configured_limits() {
        val archive =
            FakeArchive(
                ranking = (1..5).map { TechMention("t$it", 10 - it) },
                articlesByKeyword =
                    mapOf(
                        "t1" to listOf(item("a1"), item("a2"), item("a3")),
                        "t2" to listOf(item("b1")),
                        "t3" to listOf(item("c1")),
                    ),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, techLimit = 2, articlesPerTech = 2).run()

        val posted = publisher.posted!!
        assertEquals(listOf("t1", "t2"), posted.map { it.keyword })
        assertEquals(listOf("a1", "a2"), posted[0].articleGuids()) // 3 件中 上位 2 件に丸め
    }

    @Test
    fun shows_a_shared_article_only_once_across_techs() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5), TechMention("Kafka", 4)),
                articlesByKeyword =
                    mapOf(
                        "Kotlin" to listOf(item("shared"), item("kOnly")),
                        "Kafka" to listOf(item("shared"), item("kaOnly")),
                    ),
            )
        val publisher = FakePublisher()
        val store = FakePostedGuidStore()

        useCase(archive, publisher = publisher, store = store).run()

        val posted = publisher.posted!!
        assertEquals(listOf("shared", "kOnly"), posted[0].articleGuids())
        assertEquals(listOf("kaOnly"), posted[1].articleGuids()) // shared は先頭技術で消費済み
        assertEquals(setOf("shared", "kOnly", "kaOnly"), store.marked!!.toSet())
    }

    @Test
    fun skips_tech_whose_articles_are_all_already_posted_and_fills_from_next() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("t1", 9), TechMention("t2", 8), TechMention("t3", 7)),
                articlesByKeyword =
                    mapOf(
                        "t1" to listOf(item("old1")), // 全て通知済み → セクションごとスキップ
                        "t2" to listOf(item("new2")),
                        "t3" to listOf(item("new3")),
                    ),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, store = FakePostedGuidStore(alreadyPosted = setOf("old1")), techLimit = 2).run()

        assertEquals(listOf("t2", "t3"), publisher.posted!!.map { it.keyword })
    }
}
