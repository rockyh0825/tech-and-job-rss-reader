package dev.rockyh.rsswatch.notify.infrastructure

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient

class ClaudeSummarizerTest {

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var summarizer: ClaudeSummarizer

    private val baseUrl = "https://api.anthropic.com"

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        summarizer =
            ClaudeSummarizer(
                restClientBuilder = builder,
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = "claude-haiku-4-5-20251001",
                maxTokens = 256,
                systemPrompt = "3行で要約して",
            )
    }

    @Test
    fun returns_summary_extracted_from_content_text_on_success() {
        server
            .expect(requestTo("$baseUrl/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-api-key", "test-key"))
            .andExpect(header("anthropic-version", "2023-06-01"))
            .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
            .andExpect(jsonPath("$.max_tokens").value(256))
            .andExpect(jsonPath("$.system").value("3行で要約して"))
            .andRespond(
                withSuccess(
                    """{"content":[{"type":"text","text":"1行目\n2行目\n3行目"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = summarizer.summarize("タイトル", "概要")

        assertEquals("1行目\n2行目\n3行目", result.getOrNull())
        server.verify()
    }

    @Test
    fun returns_failure_on_429_rate_limit() {
        server
            .expect(requestTo("$baseUrl/v1/messages"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val result = summarizer.summarize("タイトル", "概要")

        assertTrue(result.isFailure)
    }

    @Test
    fun returns_failure_on_timeout() {
        server
            .expect(requestTo("$baseUrl/v1/messages"))
            .andRespond(withException(IOException("timeout")))

        val result = summarizer.summarize("タイトル", "概要")

        assertTrue(result.isFailure)
    }

    @Test
    fun returns_failure_when_response_has_no_content() {
        server
            .expect(requestTo("$baseUrl/v1/messages"))
            .andRespond(withSuccess("""{"content":[]}""", MediaType.APPLICATION_JSON))

        val result = summarizer.summarize("タイトル", "概要")

        assertTrue(result.isFailure)
    }

    @Test
    fun returns_failure_on_malformed_response_body() {
        server
            .expect(ExpectedCount.once(), requestTo("$baseUrl/v1/messages"))
            .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

        val result = summarizer.summarize("タイトル", "概要")

        assertTrue(result.isFailure)
    }
}
