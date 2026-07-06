package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.application.BuildDigestUseCase
import dev.rockyh.rsswatch.notify.domain.DigestSelectionPolicy
import dev.rockyh.rsswatch.notify.infrastructure.ClaudeSummarizer
import dev.rockyh.rsswatch.notify.infrastructure.DiscordWebhookClient
import dev.rockyh.rsswatch.notify.infrastructure.PostedGuidRepository
import dev.rockyh.rsswatch.shared.contract.RssItem
import dev.rockyh.rsswatch.testing.PostgresTestConfiguration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

// Kafka は到達不能ポートにして実 broker への join を防ぐ。DB は共有 PostgreSQL コンテナ。
private const val KAFKA_OFF = "spring.kafka.bootstrap-servers=localhost:1"
private const val FETCH_OFF = "rss-watch.fetch.initial-delay-ms=3600000"

/** Webhook URL 設定時は notify feature の Bean 一式が登録される(要件 4.3)。 */
@SpringBootTest(
    properties = [
        FETCH_OFF,
        KAFKA_OFF,
        "rss-watch.notify.discord-webhook-url=https://discord.example/webhook/abc",
    ],
)
@Import(PostgresTestConfiguration::class)
class NotifyEnabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun registers_all_notify_beans() {
        assertTrue(context.getBeanNamesForType(DigestScheduler::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(BuildDigestUseCase::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(DigestSelectionPolicy::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(ClaudeSummarizer::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(DiscordWebhookClient::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(PostedGuidRepository::class.java).isNotEmpty())
    }
}

/**
 * `rss-watch.notify.popular-feeds` に設定した値が [DigestSelectionPolicy] Bean まで流れていることを検証する。
 * application.yml とは別のフィード名を与え、そのフィード由来の item が優先選抜されることで、設定値が
 * ポリシーへ確かに束縛されている(dead config でない)ことを確認する。
 */
@SpringBootTest(
    properties = [
        FETCH_OFF,
        KAFKA_OFF,
        "rss-watch.notify.discord-webhook-url=https://discord.example/webhook/abc",
        "rss-watch.notify.popular-feeds=設定由来フィードA,設定由来フィードB",
    ],
)
@Import(PostgresTestConfiguration::class)
class NotifyPopularFeedsBindingTest {

    @Autowired
    lateinit var policy: DigestSelectionPolicy

    private fun item(guid: String, feedName: String): RssItem =
        RssItem(
            guid = guid,
            feedName = feedName,
            category = "tech",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary of $guid",
            publishedAt = Instant.parse("2026-07-06T07:00:00Z"),
            fetchedAt = Instant.parse("2026-07-06T08:00:00Z"),
            keywords = emptyList(),
        )

    @Test
    fun prioritizes_items_from_configured_popular_feed() {
        val candidates =
            listOf(
                item("plain", feedName = "その他フィード"),
                item("configured", feedName = "設定由来フィードA"),
            )

        val selected = policy.select(candidates, limit = 5, alreadyPosted = emptySet())

        assertEquals(listOf("configured", "plain"), selected.map { it.guid })
    }
}

/** Webhook URL 未設定なら notify の Bean は一切登録されず、アプリは通常起動する(要件 4.3)。 */
@SpringBootTest(properties = [FETCH_OFF, KAFKA_OFF])
@Import(PostgresTestConfiguration::class)
class NotifyDisabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun does_not_register_any_notify_bean() {
        assertFalse(context.getBeanNamesForType(DigestScheduler::class.java).isNotEmpty())
        assertFalse(context.getBeanNamesForType(BuildDigestUseCase::class.java).isNotEmpty())
        assertFalse(context.getBeanNamesForType(DigestSelectionPolicy::class.java).isNotEmpty())
        assertFalse(context.getBeanNamesForType(ClaudeSummarizer::class.java).isNotEmpty())
        assertFalse(context.getBeanNamesForType(DiscordWebhookClient::class.java).isNotEmpty())
        assertFalse(context.getBeanNamesForType(PostedGuidRepository::class.java).isNotEmpty())
    }
}
