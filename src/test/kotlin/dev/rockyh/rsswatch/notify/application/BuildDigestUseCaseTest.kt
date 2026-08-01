package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.FeaturedTechStore
import dev.rockyh.rsswatch.notify.domain.NotifyInterests
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.notify.domain.TechDigest
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val techRankingCategories = mutableListOf<ItemCategory>()

        override fun techRanking(category: ItemCategory, days: Int): List<TechMention> {
            techRankingCategories += category
            return ranking
        }

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> = emptyList()

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            if (category == ItemCategory.TECH) articlesByKeyword[keyword].orEmpty() else emptyList()

        override fun itemsByKeywords(
            keywords: List<String>,
            category: ItemCategory,
            days: Int,
        ): Map<String, List<RssItem>> = keywords.associateWith { itemsByKeyword(it, category, days) }
    }

    private class FakeSummarizer(private val result: Result<String>) : Summarizer {
        override fun summarize(title: String, summary: String): Result<String> = result
    }

    /** 記事 URL ごとのサムネイル。指定の無い URL は「解決できなかった」= null を返す。 */
    private class FakeThumbnailResolver(private val byUrl: Map<String, String> = emptyMap()) : ThumbnailResolver {
        val resolvedUrls = mutableListOf<String>()

        override fun resolve(articleUrl: String): String? {
            resolvedUrls += articleUrl
            return byUrl[articleUrl]
        }
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

        override fun postCtaOnly(): PostOutcome {
            ctaOnlyCount++
            return ctaOnlyOutcome
        }

        var ctaOnlyCount = 0
        var ctaOnlyOutcome = PostOutcome(emptyList())
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

    private class FakeFeaturedTechStore(private val featured: Map<String, Instant> = emptyMap()) : FeaturedTechStore {
        var marked: List<String>? = null

        override fun lastFeaturedAt(): Map<String, Instant> = featured

        override fun markFeatured(keywords: List<String>) {
            marked = keywords
        }
    }

    private fun useCase(
        archive: ArchiveQueryPort,
        summarizer: Summarizer = FakeSummarizer(Result.success("要約")),
        publisher: DigestPublisher = FakePublisher(),
        store: PostedGuidStore = FakePostedGuidStore(),
        thumbnailResolver: ThumbnailResolver = FakeThumbnailResolver(),
        interests: NotifyInterests = NotifyInterests(emptySet()),
        featuredStore: FeaturedTechStore = FakeFeaturedTechStore(),
        techLimit: Int = 3,
        articlesPerTech: Int = 3,
        windowDays: Int = 7,
        rotationCooldownDays: Int = 3,
        techPoolSize: Int = 10,
    ): BuildDigestUseCase =
        BuildDigestUseCase(
            archiveQueryPort = archive,
            summarizer = summarizer,
            webhookClient = publisher,
            postedGuidRepository = store,
            thumbnailResolver = thumbnailResolver,
            interests = interests,
            featuredTechStore = featuredStore,
            techLimit = techLimit,
            articlesPerTech = articlesPerTech,
            windowDays = windowDays,
            rotationCooldownDays = rotationCooldownDays,
            techPoolSize = techPoolSize,
            clock = Clock.fixed(now, ZoneOffset.UTC),
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
    fun ranks_candidate_techs_by_tech_article_mentions_not_by_job_mentions() {
        val archive = FakeArchive(ranking = listOf(TechMention("Kotlin", 3)), articlesByKeyword = emptyMap())

        useCase(archive).run()

        assertEquals(listOf(ItemCategory.TECH), archive.techRankingCategories)
    }

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
    fun attaches_the_resolved_thumbnail_to_each_article() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val publisher = FakePublisher()
        val resolver = FakeThumbnailResolver(mapOf("https://example.com/a" to "https://cdn.example.com/a.png"))

        useCase(archive, publisher = publisher, thumbnailResolver = resolver).run()

        assertEquals("https://cdn.example.com/a.png", publisher.posted!!.single().articles.single().thumbnailUrl)
        // サムネイルは記事ページ(記事の URL)から解決する
        assertEquals(listOf("https://example.com/a"), resolver.resolvedUrls)
    }

    @Test
    fun falls_back_to_no_thumbnail_when_it_cannot_be_resolved() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, thumbnailResolver = FakeThumbnailResolver()).run()

        assertNull(publisher.posted!!.single().articles.single().thumbnailUrl)
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
    fun posts_only_the_site_link_when_no_tech_has_articles() {
        // 候補 0 件の日も無言にはせず、サイト導線だけを届ける(記事投稿と各種記録は行わない)
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5)),
                articlesByKeyword = emptyMap(),
            )
        val publisher = FakePublisher()
        val store = FakePostedGuidStore()
        val featuredStore = FakeFeaturedTechStore()

        useCase(archive, publisher = publisher, store = store, featuredStore = featuredStore).run()

        assertNull(publisher.posted)
        assertEquals(1, publisher.ctaOnlyCount)
        assertNull(store.marked)
        assertNull(featuredStore.marked)
    }

    @Test
    fun does_not_throw_when_the_cta_only_post_fails() {
        // 導線だけの投稿が失敗しても例外にしない(翌日また試みるだけで自己回復する)
        val archive = FakeArchive(ranking = emptyList())
        val publisher = FakePublisher().apply { ctaOnlyOutcome = PostOutcome(emptyList(), RuntimeException("discord down")) }
        val store = FakePostedGuidStore()

        useCase(archive, publisher = publisher, store = store).run()

        assertEquals(1, publisher.ctaOnlyCount)
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

    @Test
    fun limits_candidates_to_the_top_ranked_pool() {
        // ランキング全件を候補にしない: プール(上位 K 件)の外の技術は、未紹介でも浮上しない
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30), TechMention("Ruby", 1)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Ruby" to listOf(item("r1"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, techPoolSize = 1).run()

        assertEquals(listOf("Python"), publisher.posted!!.map { it.keyword })
    }

    @Test
    fun keeps_interested_tech_as_candidate_even_when_ranked_below_the_pool_cutoff() {
        // 興味技術は足切りの対象外。ランキング内の実言及数を保ったまま候補に残る
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30), TechMention("Kotlin", 2)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Kotlin" to listOf(item("k1"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, interests = NotifyInterests(setOf("Kotlin")), techPoolSize = 1).run()

        val posted = publisher.posted!!
        assertEquals(listOf("Kotlin", "Python"), posted.map { it.keyword })
        assertEquals(2, posted[0].mentionCount)
    }

    @Test
    fun rejects_non_positive_tech_pool_size() {
        assertFailsWith<IllegalArgumentException> { useCase(FakeArchive(ranking = emptyList()), techPoolSize = 0) }
    }

    @Test
    fun rejects_non_positive_rotation_cooldown_days_with_the_config_key_in_the_message() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                useCase(FakeArchive(ranking = emptyList()), rotationCooldownDays = 0)
            }
        assertContains(error.message.orEmpty(), "rss-watch.notify.rotation-cooldown-days")
    }

    // --- 興味技術の優先とローテーション ---

    @Test
    fun puts_interested_tech_ahead_of_higher_ranked_tech() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30), TechMention("Kotlin", 2)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Kotlin" to listOf(item("k1"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, interests = NotifyInterests(setOf("Kotlin"))).run()

        assertEquals(listOf("Kotlin", "Python"), publisher.posted!!.map { it.keyword })
    }

    @Test
    fun includes_interested_tech_absent_from_ranking_with_zero_mention_when_it_has_fresh_articles() {
        // 記事ベースのランキングでは「ランキング外なのに新着記事がある」状態は実際には発生しないが、
        // ランキング軸を求人に戻した場合の救済機構として仕様を固定しておく
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Elixir" to listOf(item("e1"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, interests = NotifyInterests(setOf("Elixir"))).run()

        val posted = publisher.posted!!
        assertEquals(listOf("Elixir", "Python"), posted.map { it.keyword })
        assertEquals(0, posted[0].mentionCount)
    }

    @Test
    fun skips_interested_tech_absent_from_ranking_when_it_has_no_fresh_articles() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, interests = NotifyInterests(setOf("Elixir"))).run()

        assertEquals(listOf("Python"), publisher.posted!!.map { it.keyword })
    }

    @Test
    fun defers_recently_featured_tech_behind_never_featured_ones() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30), TechMention("Ruby", 1)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Ruby" to listOf(item("r1"))),
            )
        val publisher = FakePublisher()
        val featuredStore = FakeFeaturedTechStore(mapOf("Python" to now.minus(Duration.ofDays(1))))

        useCase(archive, publisher = publisher, featuredStore = featuredStore, techLimit = 1).run()

        assertEquals(listOf("Ruby"), publisher.posted!!.map { it.keyword })
    }

    @Test
    fun brings_back_high_mention_tech_once_its_cooldown_has_passed() {
        // 旧仕様(未紹介 → 紹介が古い順)では未紹介の Ruby が先だった。クールダウン(既定 3 日)が
        // 明けた注目技術は言及数で競い、全技術の一巡を待たずに戻ってくる
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Python", 30), TechMention("Ruby", 1)),
                articlesByKeyword = mapOf("Python" to listOf(item("p1")), "Ruby" to listOf(item("r1"))),
            )
        val publisher = FakePublisher()
        val featuredStore = FakeFeaturedTechStore(mapOf("Python" to now.minus(Duration.ofDays(4))))

        useCase(archive, publisher = publisher, featuredStore = featuredStore, techLimit = 1).run()

        assertEquals(listOf("Python"), publisher.posted!!.map { it.keyword })
    }

    @Test
    fun marks_featured_techs_after_successful_post() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5), TechMention("Go", 3)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a")), "Go" to listOf(item("b"))),
            )
        val featuredStore = FakeFeaturedTechStore()

        useCase(archive, featuredStore = featuredStore).run()

        assertEquals(listOf("Kotlin", "Go"), featuredStore.marked)
    }

    @Test
    fun does_not_mark_featured_when_nothing_could_be_posted() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val featuredStore = FakeFeaturedTechStore()
        val publisher = FakePublisher { PostOutcome(emptyList(), RuntimeException("discord down")) }

        useCase(archive, publisher = publisher, featuredStore = featuredStore).run()

        assertNull(featuredStore.marked)
    }

    @Test
    fun marks_featured_only_the_techs_whose_articles_actually_reached_discord() {
        // 記事ごとに 1 通ずつ投稿するので、途中で打ち切られると届かなかった技術が出る。
        // 届かなかった技術は「紹介した」とは言えないのでローテーションに乗せず、次回も候補に残す
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5), TechMention("Go", 3)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a")), "Go" to listOf(item("b"))),
            )
        val featuredStore = FakeFeaturedTechStore()
        // Kotlin の記事だけ投稿できて、Go の記事の手前で打ち切られた
        val publisher = FakePublisher { PostOutcome(listOf("a"), RuntimeException("discord down")) }

        useCase(archive, publisher = publisher, featuredStore = featuredStore).run()

        assertEquals(listOf("Kotlin"), featuredStore.marked)
    }

    @Test
    fun still_marks_posted_and_does_not_throw_when_mark_featured_fails() {
        // markPosted と markFeatured は別トランザクション。ローテーション記録の一時失敗は
        // 配信自体の成功を壊さない(次回成功時に自己回復する)
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 1)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a"))),
            )
        val store = FakePostedGuidStore()
        val throwingFeaturedStore =
            object : FeaturedTechStore {
                override fun lastFeaturedAt(): Map<String, Instant> = emptyMap()

                override fun markFeatured(keywords: List<String>) = throw RuntimeException("db down")
            }

        useCase(archive, store = store, featuredStore = throwingFeaturedStore).run()

        assertEquals(listOf("a"), store.marked)
    }

    @Test
    fun flags_digests_of_interested_techs() {
        val archive =
            FakeArchive(
                ranking = listOf(TechMention("Kotlin", 5), TechMention("Python", 3)),
                articlesByKeyword = mapOf("Kotlin" to listOf(item("a")), "Python" to listOf(item("b"))),
            )
        val publisher = FakePublisher()

        useCase(archive, publisher = publisher, interests = NotifyInterests(setOf("Kotlin"))).run()

        val posted = publisher.posted!!
        assertEquals(listOf(true, false), posted.map { it.interested })
    }
}
