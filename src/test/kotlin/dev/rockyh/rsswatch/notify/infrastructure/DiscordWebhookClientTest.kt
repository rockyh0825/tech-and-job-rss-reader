package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.TechDigest
import kotlin.test.assertEquals
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
        maxTotalEmbedChars: Int = 6000,
    ): DiscordWebhookClient =
        DiscordWebhookClient(
            restClientBuilder = builder,
            webhookUrl = webhookUrl,
            siteUrl = siteUrl,
            maxRetries = maxRetries,
            sleeper = { sleeps.add(it) },
            maxTotalEmbedChars = maxTotalEmbedChars,
        )

    private fun article(title: String, url: String, summary: String?): DigestArticle = DigestArticle(title, url, summary)

    private val digests =
        listOf(
            TechDigest(
                keyword = "Kotlin",
                mentionCount = 5,
                articles =
                    listOf(
                        article("Kotlin の記事", "https://example.com/1", "1行目\n2行目\n3行目"),
                        article("要約なしの記事", "https://example.com/2", null),
                    ),
            ),
        )

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        sleeps.clear()
    }

    @Test
    fun posts_articles_as_embeds_with_tech_author_and_fixed_summary_field_then_cta() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(method(HttpMethod.POST))
            // 記事 2 件 + 末尾 CTA = 3 embed
            .andExpect(jsonPath("$.embeds.length()").value(3))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🧩 Kotlin ・ 求人 5 件で言及"))
            .andExpect(jsonPath("$.embeds[0].title").value("Kotlin の記事"))
            .andExpect(jsonPath("$.embeds[0].url").value("https://example.com/1"))
            .andExpect(jsonPath("$.embeds[0].fields[0].name").value("要約"))
            .andExpect(jsonPath("$.embeds[0].fields[0].value").value("1行目\n2行目\n3行目"))
            .andExpect(jsonPath("$.embeds[1].title").value("要約なしの記事"))
            // 末尾 CTA embed はサイトへのリンク
            .andExpect(jsonPath("$.embeds[2].url").value(siteUrl))
            .andRespond(withSuccess())

        val result = client().post(digests)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun prefixes_star_to_author_label_for_interested_tech() {
        val digest = listOf(TechDigest("AWS", 12, listOf(article("記事", "https://example.com/1", null)), interested = true))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("⭐ AWS ・ 求人 12 件で言及"))
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun shows_fresh_articles_label_instead_of_zero_mentions() {
        // 求人に出ていない興味技術(mentionCount=0)は「求人 0 件で言及」ではなく新着記事の見出しにする
        val digest = listOf(TechDigest("Elixir", 0, listOf(article("記事", "https://example.com/1", null)), interested = true))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("⭐ Elixir ・ 新着記事"))
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun omits_summary_field_when_summary_is_null() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[1].fields").doesNotExist())
            .andRespond(withSuccess())

        val result = client().post(digests)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun omits_summary_field_when_summary_is_blank() {
        // 空白のみの要約は field ごと省く(field value 空は Discord が 400 で弾くため)
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article("記事", "https://example.com/1", "   \n "))))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields").doesNotExist())
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun appends_cta_embed_linking_to_site_as_the_last_embed() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[2].url").value(siteUrl))
            .andExpect(jsonPath("$.embeds[2].author").doesNotExist())
            .andRespond(withSuccess())

        val result = client().post(digests)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun retries_after_429_respecting_retry_after_then_succeeds() {
        val headers = HttpHeaders().apply { add(HttpHeaders.RETRY_AFTER, "2") }
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers))
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withSuccess())

        val result = client(maxRetries = 2).post(digests)

        assertTrue(result.isSuccess)
        assertEquals(listOf(2000L), sleeps)
        server.verify()
    }

    @Test
    fun returns_failure_after_exhausting_retries_on_persistent_429() {
        server
            .expect(ExpectedCount.times(3), requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val result = client(maxRetries = 2).post(digests)

        assertTrue(result.isFailure)
        // 初回 + 2 リトライ = 3 回試行、リトライ前に 2 回スリープ
        assertEquals(2, sleeps.size)
        server.verify()
    }

    @Test
    fun returns_failure_on_non_retryable_error() {
        server
            .expect(ExpectedCount.once(), requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST))

        val result = client().post(digests)

        assertTrue(result.isFailure)
        assertTrue(sleeps.isEmpty())
        server.verify()
    }

    @Test
    fun returns_failure_and_does_not_send_when_webhook_url_is_blank() {
        // MockRestServiceServer に expect を一切設定しない = リクエストが飛べば verify で検出される

        val result = client(webhookUrl = "   ").post(digests)

        assertTrue(result.isFailure)
        server.verify()
    }

    @Test
    fun caps_article_embeds_at_nine_and_still_appends_cta() {
        // 記事 11 件 → 記事 embed は MAX_EMBEDS-1=9 件に丸め、末尾 CTA と合わせて計 10 embed
        val manyArticles = (1..11).map { article("記事 $it", "https://example.com/$it", null) }
        val digest = listOf(TechDigest(keyword = "Kotlin", mentionCount = 1, articles = manyArticles))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(10))
            .andExpect(jsonPath("$.embeds[9].url").value(siteUrl))
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun clamps_title_to_max_length_when_it_exceeds_the_limit() {
        val longTitle = "あ".repeat(300)
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article(longTitle, "https://example.com/long", null))))
        // 256 文字以内(255 文字 + 省略記号)に切り詰められること
        val expectedTitle = "あ".repeat(255) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertEquals(256, expectedTitle.length)
        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun drops_trailing_article_embeds_when_total_character_count_exceeds_limit() {
        // 要約は field(上限 1024)へ入るので 1 記事あたり約 1051 文字。CTA ぶんも予約するため、
        // 合計上限 3000 に収まる先頭 2 件だけ載せ、末尾に CTA(計 3)。
        val bigArticles = (1..4).map { article("記事 $it", "https://example.com/$it", "x".repeat(2000)) }
        val digest = listOf(TechDigest("Kotlin", 1, bigArticles))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(3))
            .andExpect(jsonPath("$.embeds[2].url").value(siteUrl))
            .andRespond(withSuccess())

        val result = client(maxTotalEmbedChars = 3000).post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun clamp_does_not_split_surrogate_pairs() {
        // 😀(U+1F600)は UTF-16 で 2 code unit。256 境界がペアの途中に落ちても壊れた文字を残さない
        val emojiTitle = "😀".repeat(200) // 400 code unit
        val digest = listOf(TechDigest("Kotlin", 1, listOf(article(emojiTitle, "https://example.com/emoji", null))))
        // 255 code unit 枠 → 直前が high surrogate なので 254 で切る = 完全な 😀 ×127 + 省略記号
        val expectedTitle = "😀".repeat(127) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())

        val result = client().post(digest)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun uses_default_one_second_wait_when_429_has_no_retry_after_header_or_body() {
        // Retry-After ヘッダ無し・ボディ無しの 429 → 既定 1000ms を採用してリトライ(即リトライで叩き続けない)
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withSuccess())

        val result = client(maxRetries = 2).post(digests)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1000L), sleeps)
        server.verify()
    }

    @Test
    fun keeps_first_article_via_fallback_when_no_article_fits_but_still_appends_cta() {
        // 合計上限を極小(10)にすると CTA 予約だけで超過し、どの記事も収まらない。
        // 空にせず先頭 1 件だけ残すフォールバックが働き、末尾に CTA を足して計 2 embed になること。
        val articles = (1..3).map { article("記事 $it", "https://example.com/$it", "x".repeat(20)) }
        val digest = listOf(TechDigest("Kotlin", 1, articles))
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(2))
            .andExpect(jsonPath("$.embeds[0].title").value("記事 1"))
            .andExpect(jsonPath("$.embeds[1].url").value(siteUrl))
            .andRespond(withSuccess())

        val result = client(maxTotalEmbedChars = 10).post(digest)

        assertTrue(result.isSuccess)
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
        server
            .expect(requestTo(webhookUrl))
            .andRespond(withSuccess())

        val result = client(maxRetries = 2).post(digests)

        assertTrue(result.isSuccess)
        assertEquals(listOf(3000L), sleeps)
        server.verify()
    }
}
