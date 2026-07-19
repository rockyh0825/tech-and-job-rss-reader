package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.application.BuildCncfDigestUseCase
import dev.rockyh.rsswatch.notify.application.BuildDigestUseCase
import dev.rockyh.rsswatch.notify.domain.NotifyInterests
import dev.rockyh.rsswatch.notify.infrastructure.ClaudeSummarizer
import dev.rockyh.rsswatch.notify.infrastructure.CncfDiscordWebhookClient
import dev.rockyh.rsswatch.notify.infrastructure.DiscordWebhookClient
import dev.rockyh.rsswatch.notify.infrastructure.FeaturedTechRepository
import dev.rockyh.rsswatch.notify.infrastructure.OgpThumbnailResolver
import dev.rockyh.rsswatch.notify.infrastructure.PostedGuidRepository
import dev.rockyh.rsswatch.testing.PostgresTestConfiguration
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
private const val TECH_WEBHOOK = "rss-watch.notify.discord-webhook-url=https://discord.example/webhook/abc"
private const val CNCF_WEBHOOK = "rss-watch.notify.cncf.discord-webhook-url=https://discord.example/webhook/cncf"

private fun ApplicationContext.has(type: Class<*>): Boolean = getBeanNamesForType(type).isNotEmpty()

/** 既存側の Webhook URL のみ設定時は、既存ダイジェスト + 共有部品が登録され、CNCF 側は登録されない。 */
@SpringBootTest(properties = [FETCH_OFF, KAFKA_OFF, TECH_WEBHOOK])
@Import(PostgresTestConfiguration::class)
class NotifyEnabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun registers_all_notify_beans() {
        assertTrue(context.has(DigestScheduler::class.java))
        assertTrue(context.has(BuildDigestUseCase::class.java))
        assertTrue(context.has(ClaudeSummarizer::class.java))
        assertTrue(context.has(DiscordWebhookClient::class.java))
        assertTrue(context.has(OgpThumbnailResolver::class.java))
        assertTrue(context.has(PostedGuidRepository::class.java))
        assertTrue(context.has(FeaturedTechRepository::class.java))
        assertTrue(context.has(NotifyInterests::class.java))
    }

    @Test
    fun does_not_register_cncf_digest_beans() {
        assertFalse(context.has(CncfDigestScheduler::class.java))
        assertFalse(context.has(BuildCncfDigestUseCase::class.java))
        assertFalse(context.has(CncfDiscordWebhookClient::class.java))
    }
}

/** CNCF 側の Webhook URL のみ設定時は、CNCF ダイジェスト + 共有部品が登録され、既存側固有の Bean は登録されない(要件 4.2)。 */
@SpringBootTest(properties = [FETCH_OFF, KAFKA_OFF, CNCF_WEBHOOK])
@Import(PostgresTestConfiguration::class)
class CncfNotifyOnlyEnabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun registers_cncf_digest_beans_and_shared_beans() {
        assertTrue(context.has(CncfDigestScheduler::class.java))
        assertTrue(context.has(BuildCncfDigestUseCase::class.java))
        assertTrue(context.has(CncfDiscordWebhookClient::class.java))
        // 共有部品(要約・サムネイル・投稿済み管理)はどちらか一方が有効なら登録される
        assertTrue(context.has(ClaudeSummarizer::class.java))
        assertTrue(context.has(OgpThumbnailResolver::class.java))
        assertTrue(context.has(PostedGuidRepository::class.java))
    }

    @Test
    fun does_not_register_tech_digest_beans() {
        assertFalse(context.has(DigestScheduler::class.java))
        assertFalse(context.has(BuildDigestUseCase::class.java))
        assertFalse(context.has(DiscordWebhookClient::class.java))
        assertFalse(context.has(FeaturedTechRepository::class.java))
        assertFalse(context.has(NotifyInterests::class.java))
    }
}

/** 両方の Webhook URL 設定時は、両ダイジェストの Bean が登録される。 */
@SpringBootTest(properties = [FETCH_OFF, KAFKA_OFF, TECH_WEBHOOK, CNCF_WEBHOOK])
@Import(PostgresTestConfiguration::class)
class BothNotifyEnabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun registers_both_digest_beans() {
        assertTrue(context.has(DigestScheduler::class.java))
        assertTrue(context.has(BuildDigestUseCase::class.java))
        assertTrue(context.has(DiscordWebhookClient::class.java))
        assertTrue(context.has(CncfDigestScheduler::class.java))
        assertTrue(context.has(BuildCncfDigestUseCase::class.java))
        assertTrue(context.has(CncfDiscordWebhookClient::class.java))
        assertTrue(context.has(ClaudeSummarizer::class.java))
        assertTrue(context.has(OgpThumbnailResolver::class.java))
        assertTrue(context.has(PostedGuidRepository::class.java))
    }
}

/** どちらの Webhook URL も未設定なら notify の Bean は一切登録されず、アプリは通常起動する(要件 4.3)。 */
@SpringBootTest(properties = [FETCH_OFF, KAFKA_OFF])
@Import(PostgresTestConfiguration::class)
class NotifyDisabledTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun does_not_register_any_notify_bean() {
        assertFalse(context.has(DigestScheduler::class.java))
        assertFalse(context.has(BuildDigestUseCase::class.java))
        assertFalse(context.has(ClaudeSummarizer::class.java))
        assertFalse(context.has(DiscordWebhookClient::class.java))
        assertFalse(context.has(OgpThumbnailResolver::class.java))
        assertFalse(context.has(PostedGuidRepository::class.java))
        assertFalse(context.has(FeaturedTechRepository::class.java))
        assertFalse(context.has(NotifyInterests::class.java))
        assertFalse(context.has(CncfDigestScheduler::class.java))
        assertFalse(context.has(BuildCncfDigestUseCase::class.java))
        assertFalse(context.has(CncfDiscordWebhookClient::class.java))
    }
}
