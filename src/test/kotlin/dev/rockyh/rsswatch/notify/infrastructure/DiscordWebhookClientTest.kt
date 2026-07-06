package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.DigestEntry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
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

    private fun client(maxRetries: Int = 2): DiscordWebhookClient =
        DiscordWebhookClient(
            restClientBuilder = builder,
            webhookUrl = webhookUrl,
            maxRetries = maxRetries,
            sleeper = { sleeps.add(it) },
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
}
