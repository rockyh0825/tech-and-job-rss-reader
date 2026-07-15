package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.TechDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class DiscordWebhookClientTest {

    private val webhookUrl = "https://discord.example/webhook/abc"
    private val siteUrl = "https://site.example/"

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private val sleeps = mutableListOf<Long>()

    private fun client(
        maxRetries: Int = 2,
        webhookUrl: String = this.webhookUrl,
    ): DiscordWebhookClient =
        DiscordWebhookClient(
            restClientBuilder = builder,
            webhookUrl = webhookUrl,
            siteUrl = siteUrl,
            maxRetries = maxRetries,
            sleeper = { sleeps.add(it) },
        )

    private fun article(guid: String, title: String, url: String, summary: String?): DigestArticle =
        DigestArticle(guid = guid, title = title, url = url, summary = summary)

    /** 記事 2 件を含む技術グループ 1 件 → 投稿は「記事 2 通 + リンク 1 通」= 計 3 通になる。 */
    private val digests =
        listOf(
            TechDigest(
                keyword = "Kotlin",
                mentionCount = 5,
                articles =
                    listOf(
                        article("g1", "Kotlin の記事", "https://example.com/1", "1行目\n2行目\n3行目"),
                        article("g2", "要約なしの記事", "https://example.com/2", null),
                    ),
            ),
        )

    /** リトライ挙動だけを見たいテスト用の、記事 1 件のみのダイジェスト。 */
    private val oneArticle =
        listOf(TechDigest("Kotlin", 1, listOf(article("g1", "記事", "https://example.com/1", null))))

    /** 中身を検証しない成功レスポンスの expectation を [count] 通ぶん積む(登録順に消費される)。 */
    private fun expectSuccessfulPosts(count: Int) {
        repeat(count) { server.expect(requestTo(webhookUrl)).andRespond(withSuccess()) }
    }

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        sleeps.clear()
    }

    @Test
    fun posts_one_message_per_article_then_a_final_message_with_the_site_link() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.embeds.length()").value(1))
            .andExpect(jsonPath("$.embeds[0].title").value("Kotlin の記事"))
            .andRespond(withSuccess())
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(1))
            .andExpect(jsonPath("$.embeds[0].title").value("要約なしの記事"))
            .andRespond(withSuccess())
        // 記事を全部送り終えてから、最後にサイトへのリンクを 1 通
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(1))
            .andExpect(jsonPath("$.embeds[0].url").value(siteUrl))
            .andExpect(jsonPath("$.embeds[0].author").doesNotExist())
            .andRespond(withSuccess())

        val outcome = client().post(digests)

        assertNull(outcome.failure)
        assertEquals(listOf("g1", "g2"), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun each_article_message_carries_its_tech_author_and_fixed_summary_field() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🧩 Kotlin ・ 求人 5 件で言及"))
            .andExpect(jsonPath("$.embeds[0].url").value("https://example.com/1"))
            .andExpect(jsonPath("$.embeds[0].fields[0].name").value("要約"))
            .andExpect(jsonPath("$.embeds[0].fields[0].value").value("1行目\n2行目\n3行目"))
            .andRespond(withSuccess())
        expectSuccessfulPosts(2)

        val outcome = client().post(digests)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun posts_articles_of_every_tech_group_in_ranking_order() {
        val twoTechs =
            listOf(
                TechDigest("Kotlin", 5, listOf(article("g1", "Kotlin 記事", "https://example.com/1", null))),
                TechDigest("Go", 3, listOf(article("g2", "Go 記事", "https://example.com/2", null))),
            )
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🧩 Kotlin ・ 求人 5 件で言及"))
            .andRespond(withSuccess())
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🧩 Go ・ 求人 3 件で言及"))
            .andRespond(withSuccess())
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].url").value(siteUrl))
            .andRespond(withSuccess())

        val outcome = client().post(twoTechs)

        assertEquals(listOf("g1", "g2"), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun omits_summary_field_when_summary_is_null() {
        expectSuccessfulPosts(1)
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields").doesNotExist())
            .andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client().post(digests)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun omits_summary_field_when_summary_is_blank() {
        // 空白のみの要約は field ごと省く(field value 空は Discord が 400 で弾くため)
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article("g1", "記事", "https://example.com/1", "   \n "))))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields").doesNotExist())
            .andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client().post(digest)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun does_not_post_anything_when_there_are_no_articles() {
        // 記事が 1 件も無いならリンクだけ送っても意味がない。
        // expectation を積まない = リクエストが飛べば verify で検出される
        val outcome = client().post(emptyList())

        assertEquals(emptyList(), outcome.postedGuids)
        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun stops_posting_and_skips_the_site_link_when_an_article_fails() {
        // 1 通目は成功、2 通目が 400 → 3 通目(リンク)は送らずに打ち切る
        expectSuccessfulPosts(1)
        server.expect(requestTo(webhookUrl)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        val outcome = client().post(digests)

        assertNotNull(outcome.failure)
        // 投稿できた 1 件だけを通知済みとして返す(未投稿の g2 は次回に回す)
        assertEquals(listOf("g1"), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun reports_articles_as_posted_when_only_the_site_link_fails() {
        // 記事は全部投稿できている以上、リンクが落ちても通知済みとして記録させる(重複投稿を防ぐ)
        expectSuccessfulPosts(2)
        server.expect(requestTo(webhookUrl)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        val outcome = client().post(digests)

        assertEquals(listOf("g1", "g2"), outcome.postedGuids)
        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun retries_after_429_respecting_retry_after_then_succeeds() {
        val headers = HttpHeaders().apply { add(HttpHeaders.RETRY_AFTER, "2") }
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers))
        // リトライぶん + リンク
        expectSuccessfulPosts(2)

        val outcome = client(maxRetries = 2).post(oneArticle)

        assertNull(outcome.failure)
        assertEquals(listOf("g1"), outcome.postedGuids)
        assertEquals(listOf(2000L), sleeps)
        server.verify()
    }

    @Test
    fun returns_failure_after_exhausting_retries_on_persistent_429() {
        server
            .expect(ExpectedCount.times(3), requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val outcome = client(maxRetries = 2).post(oneArticle)

        assertNotNull(outcome.failure)
        assertEquals(emptyList(), outcome.postedGuids)
        // 初回 + 2 リトライ = 3 回試行、リトライ前に 2 回スリープ
        assertEquals(2, sleeps.size)
        server.verify()
    }

    @Test
    fun returns_failure_on_non_retryable_error() {
        server
            .expect(ExpectedCount.once(), requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST))

        val outcome = client().post(oneArticle)

        assertNotNull(outcome.failure)
        assertEquals(emptyList(), outcome.postedGuids)
        assertTrue(sleeps.isEmpty())
        server.verify()
    }

    @Test
    fun returns_failure_and_does_not_send_when_webhook_url_is_blank() {
        // MockRestServiceServer に expect を一切設定しない = リクエストが飛べば verify で検出される

        val outcome = client(webhookUrl = "   ").post(digests)

        assertNotNull(outcome.failure)
        assertEquals(emptyList(), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun posts_every_article_without_the_old_ten_embed_cap() {
        // 1 通 1 記事にしたので、旧「10 embed/通」「合計 6000 文字/通」の制約で記事を切り捨てる必要はない。
        // 記事 11 件 → 記事 11 通 + リンク 1 通 = 12 通すべて投稿される
        val manyArticles = (1..11).map { article("g$it", "記事 $it", "https://example.com/$it", "x".repeat(2000)) }
        val digest = listOf(TechDigest(keyword = "Kotlin", mentionCount = 1, articles = manyArticles))
        expectSuccessfulPosts(12)

        val outcome = client().post(digest)

        assertNull(outcome.failure)
        assertEquals((1..11).map { "g$it" }, outcome.postedGuids)
        server.verify()
    }

    @Test
    fun clamps_title_to_max_length_when_it_exceeds_the_limit() {
        val longTitle = "あ".repeat(300)
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article("g1", longTitle, "https://example.com/long", null))))
        // 256 文字以内(255 文字 + 省略記号)に切り詰められること
        val expectedTitle = "あ".repeat(255) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client().post(digest)

        assertEquals(256, expectedTitle.length)
        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun clamps_summary_field_to_max_length_when_it_exceeds_the_limit() {
        // 1 通 1 記事でも、単一 embed の field value 上限(1024)は Discord の制約として残る
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article("g1", "記事", "https://example.com/1", "x".repeat(2000)))))
        val expectedSummary = "x".repeat(1023) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields[0].value").value(expectedSummary))
            .andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client().post(digest)

        assertEquals(1024, expectedSummary.length)
        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun clamp_does_not_split_surrogate_pairs() {
        // 😀(U+1F600)は UTF-16 で 2 code unit。256 境界がペアの途中に落ちても壊れた文字を残さない
        val emojiTitle = "😀".repeat(200) // 400 code unit
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article("g1", emojiTitle, "https://example.com/emoji", null))))
        // 255 code unit 枠 → 直前が high surrogate なので 254 で切る = 完全な 😀 ×127 + 省略記号
        val expectedTitle = "😀".repeat(127) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client().post(digest)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun uses_default_one_second_wait_when_429_has_no_retry_after_header_or_body() {
        // Retry-After ヘッダ無し・ボディ無しの 429 → 既定 1000ms を採用してリトライ(即リトライで叩き続けない)
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        expectSuccessfulPosts(2)

        val outcome = client(maxRetries = 2).post(oneArticle)

        assertNull(outcome.failure)
        assertEquals(listOf(1000L), sleeps)
        server.verify()
    }

    @Test
    fun retries_using_retry_after_from_json_body_when_header_absent() {
        // Retry-After ヘッダ無し・ボディに retry_after のみ → ボディ値を尊重してリトライ
        server
            .expect(requestTo(webhookUrl))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .body("""{"message":"rate limited","retry_after":3,"global":false}""")
                    .contentType(MediaType.APPLICATION_JSON),
            )
        expectSuccessfulPosts(2)

        val outcome = client(maxRetries = 2).post(oneArticle)

        assertNull(outcome.failure)
        assertEquals(listOf(3000L), sleeps)
        server.verify()
    }

    @Test
    fun retry_budget_is_counted_per_message_so_a_429_on_one_article_does_not_starve_the_next() {
        // 記事 1 が 429→成功、記事 2 も 429→成功。リトライ回数は 1 通ごとに数え直される
        server.expect(requestTo(webhookUrl)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())
        expectSuccessfulPosts(1)

        val outcome = client(maxRetries = 1).post(digests)

        assertNull(outcome.failure)
        assertEquals(listOf("g1", "g2"), outcome.postedGuids)
        assertEquals(listOf(1000L, 1000L), sleeps)
        server.verify()
    }
}
