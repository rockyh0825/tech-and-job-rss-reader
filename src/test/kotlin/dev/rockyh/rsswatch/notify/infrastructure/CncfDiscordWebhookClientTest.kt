package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.CncfDigestEntry
import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class CncfDiscordWebhookClientTest {

    private val webhookUrl = "https://discord.example/webhook/cncf"
    private val ctaUrl = "https://www.cncf.io/projects/"

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private val sleeps = mutableListOf<Long>()

    private fun client(
        maxRetries: Int = 2,
        webhookUrl: String = this.webhookUrl,
    ): CncfDiscordWebhookClient =
        CncfDiscordWebhookClient(
            restClientBuilder = builder,
            webhookUrl = webhookUrl,
            ctaUrl = ctaUrl,
            maxRetries = maxRetries,
            sleeper = { sleeps.add(it) },
        )

    private fun entry(
        guid: String,
        title: String = "title of $guid",
        summary: String? = null,
        thumbnailUrl: String? = null,
        mentions: List<CncfMention> = emptyList(),
    ): CncfDigestEntry =
        CncfDigestEntry(
            article =
                DigestArticle(
                    guid = guid,
                    title = title,
                    url = "https://example.com/$guid",
                    summary = summary,
                    thumbnailUrl = thumbnailUrl,
                ),
            mentions = mentions,
        )

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        sleeps.clear()
    }

    @Test
    fun posts_one_message_per_article_then_a_final_message_with_the_cncf_link() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.embeds.length()").value(1))
            .andExpect(jsonPath("$.embeds[0].title").value("title of g1"))
            .andRespond(withSuccess())
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].title").value("title of g2"))
            .andRespond(withSuccess())
        // 記事を全部送り終えてから、最後に CNCF プロジェクト一覧へのリンクを 1 通
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].url").value(ctaUrl))
            .andExpect(jsonPath("$.embeds[0].author").doesNotExist())
            .andRespond(withSuccess())

        val outcome = client().post(listOf(entry("g1"), entry("g2")))

        assertNull(outcome.failure)
        assertEquals(listOf("g1", "g2"), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun shows_maturity_badge_of_the_mentioned_project_in_author_line() {
        val entries =
            listOf(
                entry("g1", mentions = listOf(CncfMention("Kepler", CncfMaturity.SANDBOX))),
            )
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🌱 Sandbox: Kepler"))
            .andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(entries)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun appends_up_to_two_more_project_names_after_the_lowest_maturity_badge() {
        val entries =
            listOf(
                entry(
                    "g1",
                    mentions =
                        listOf(
                            CncfMention("Kepler", CncfMaturity.SANDBOX),
                            CncfMention("OpenTelemetry", CncfMaturity.INCUBATING),
                            CncfMention("Kubernetes", CncfMaturity.GRADUATED),
                            CncfMention("Prometheus", CncfMaturity.GRADUATED),
                        ),
                ),
            )
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("🌱 Sandbox: Kepler ・ OpenTelemetry, Kubernetes"))
            .andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(entries)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun shows_generic_cncf_author_line_for_articles_without_project_mentions() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].author.name").value("☸️ CNCF"))
            .andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(listOf(entry("g1")))

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun carries_summary_field_and_thumbnail_like_the_tech_digest() {
        val entries =
            listOf(
                entry(
                    "g1",
                    summary = "1行目\n2行目\n3行目",
                    thumbnailUrl = "https://example.com/ogp.png",
                ),
            )
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields[0].name").value("要約"))
            .andExpect(jsonPath("$.embeds[0].fields[0].value").value("1行目\n2行目\n3行目"))
            .andExpect(jsonPath("$.embeds[0].thumbnail.url").value("https://example.com/ogp.png"))
            .andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(entries)

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun omits_summary_field_and_thumbnail_when_absent() {
        server
            .expect(requestTo(webhookUrl))
            .andExpect(jsonPath("$.embeds[0].fields").doesNotExist())
            .andExpect(jsonPath("$.embeds[0].thumbnail").doesNotExist())
            .andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(listOf(entry("g1")))

        assertNull(outcome.failure)
        server.verify()
    }

    @Test
    fun retries_on_429_respecting_retry_after_then_succeeds() {
        server
            .expect(requestTo(webhookUrl))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, "1") }),
            )
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())

        val outcome = client().post(listOf(entry("g1")))

        assertNull(outcome.failure)
        assertEquals(listOf("g1"), outcome.postedGuids)
        assertEquals(listOf(1000L), sleeps)
        server.verify()
    }

    @Test
    fun aborts_and_reports_posted_guids_when_the_webhook_is_gone() {
        server.expect(requestTo(webhookUrl)).andRespond(withSuccess())
        server.expect(requestTo(webhookUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND))

        val outcome = client().post(listOf(entry("g1"), entry("g2")))

        assertNotNull(outcome.failure)
        assertEquals(listOf("g1"), outcome.postedGuids)
        server.verify()
    }

    @Test
    fun skips_posting_when_webhook_url_is_blank() {
        val outcome = client(webhookUrl = "").post(listOf(entry("g1")))

        assertNotNull(outcome.failure)
        assertEquals(emptyList(), outcome.postedGuids)
        server.verify()
    }
}
