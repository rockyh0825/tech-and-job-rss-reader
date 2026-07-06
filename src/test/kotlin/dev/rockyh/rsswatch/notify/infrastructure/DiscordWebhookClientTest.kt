package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.DigestEntry
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
            maxRetries = maxRetries,
            sleeper = { sleeps.add(it) },
            maxTotalEmbedChars = maxTotalEmbedChars,
        )

    private val entries =
        listOf(
            DigestEntry(
                title = "Kotlin の記事",
                url = "https://example.com/1",
                summary = "1行目\n2行目\n3行目",
                keywords = listOf("Kotlin", "Kafka"),
            ),
            DigestEntry(
                title = "要約なしの記事",
                url = "https://example.com/2",
                summary = null,
                keywords = listOf("Go"),
            ),
        )

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        sleeps.clear()
    }

    @Test
    fun posts_all_entries_as_embeds_in_a_single_request() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.embeds.length()").value(2))
            .andExpect(jsonPath("$.embeds[0].title").value("Kotlin の記事"))
            .andExpect(jsonPath("$.embeds[0].url").value("https://example.com/1"))
            .andExpect(jsonPath("$.embeds[0].description").value("1行目\n2行目\n3行目"))
            .andExpect(jsonPath("$.embeds[0].fields[0].value").value("Kotlin, Kafka"))
            .andExpect(jsonPath("$.embeds[1].title").value("要約なしの記事"))
            .andRespond(withSuccess())

        val result = client().post(entries)

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

        val result = client(maxRetries = 2).post(entries)

        assertTrue(result.isSuccess)
        assertEquals(listOf(2000L), sleeps)
        server.verify()
    }

    @Test
    fun returns_failure_after_exhausting_retries_on_persistent_429() {
        server
            .expect(ExpectedCount.times(3), requestTo(webhookUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val result = client(maxRetries = 2).post(entries)

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

        val result = client().post(entries)

        assertTrue(result.isFailure)
        assertTrue(sleeps.isEmpty())
        server.verify()
    }

    @Test
    fun omits_description_when_summary_is_null() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[1].description").doesNotExist())
            .andRespond(withSuccess())

        val result = client().post(entries)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun returns_failure_and_does_not_send_when_webhook_url_is_blank() {
        // MockRestServiceServer に expect を一切設定しない = リクエストが飛べば verify で検出される

        val result = client(webhookUrl = "   ").post(entries)

        assertTrue(result.isFailure)
        server.verify()
    }

    @Test
    fun caps_embeds_at_ten_when_more_entries_are_given() {
        val elevenEntries =
            (1..11).map {
                DigestEntry(
                    title = "記事 $it",
                    url = "https://example.com/$it",
                    summary = null,
                    keywords = emptyList(),
                )
            }
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(10))
            .andRespond(withSuccess())

        val result = client().post(elevenEntries)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun clamps_title_to_max_length_when_it_exceeds_the_limit() {
        val longTitle = "あ".repeat(300)
        val entry =
            listOf(
                DigestEntry(
                    title = longTitle,
                    url = "https://example.com/long",
                    summary = null,
                    keywords = emptyList(),
                ),
            )
        // 256 文字以内(255 文字 + 省略記号)に切り詰められること
        val expectedTitle = "あ".repeat(255) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())

        val result = client().post(entry)

        assertEquals(256, expectedTitle.length)
        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun drops_trailing_embeds_when_total_character_count_exceeds_limit() {
        // description 2500 文字 × 4 件 = 10000 > 6000。合計 6000 に収まる先頭 2 件だけ載せる
        val bigEntries =
            (1..4).map {
                DigestEntry(
                    title = "記事 $it",
                    url = "https://example.com/$it",
                    summary = "x".repeat(2500),
                    keywords = emptyList(),
                )
            }
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(2))
            .andRespond(withSuccess())

        val result = client().post(bigEntries)

        assertTrue(result.isSuccess)
        server.verify()
    }

    @Test
    fun clamp_does_not_split_surrogate_pairs() {
        // 😀(U+1F600)は UTF-16 で 2 code unit。256 境界がペアの途中に落ちても壊れた文字を残さない
        val emojiTitle = "😀".repeat(200) // 400 code unit
        val entry =
            listOf(
                DigestEntry(
                    title = emojiTitle,
                    url = "https://example.com/emoji",
                    summary = null,
                    keywords = emptyList(),
                ),
            )
        // 255 code unit 枠 → 直前が high surrogate なので 254 で切る = 完全な 😀 ×127 + 省略記号
        val expectedTitle = "😀".repeat(127) + "…"
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value(expectedTitle))
            .andRespond(withSuccess())

        val result = client().post(entry)

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

        val result = client(maxRetries = 2).post(entries)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1000L), sleeps)
        server.verify()
    }

    @Test
    fun keeps_only_the_first_embed_via_fallback_when_no_embed_fits_the_total_limit() {
        // 合計上限を極小(10)にすると、どの embed も単体で上限を超え通常ループでは 1 件も収まらない。
        // このとき空ペイロード({"embeds":[]})=400 を避けるフォールバックが働き、先頭 1 件だけが載ること。
        val entriesExceedingLimit =
            (1..3).map {
                DigestEntry(
                    title = "記事 $it",
                    url = "https://example.com/$it",
                    summary = "x".repeat(20), // 単体で characterCount > 10 になり通常ループでは 1 件も収まらない
                    keywords = emptyList(),
                )
            }
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds.length()").value(1))
            .andExpect(jsonPath("$.embeds[0].title").value("記事 1"))
            .andExpect(jsonPath("$.embeds[0].url").value("https://example.com/1"))
            .andRespond(withSuccess())

        val result = client(maxTotalEmbedChars = 10).post(entriesExceedingLimit)

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

        val result = client(maxRetries = 2).post(entries)

        assertTrue(result.isSuccess)
        assertEquals(listOf(3000L), sleeps)
        server.verify()
    }
}
