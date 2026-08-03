package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.spring.jdbc.SpringJdbcKueryClient
import dev.rockyh.rsswatch.notify.domain.DiscordMessageRef
import dev.rockyh.rsswatch.testing.SharedPostgresContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

class DiscordMessageRepositoryTest {

    private lateinit var kueryClient: KueryBlockingClient

    private val now: Instant = Instant.parse("2026-08-04T08:00:00Z")

    private fun repositoryAt(instant: Instant): DiscordMessageRepository =
        DiscordMessageRepository(kueryClient, Clock.fixed(instant, ZoneOffset.UTC))

    private fun message(
        guid: String,
        messageId: String,
        channelId: String = "ch-1",
    ): DiscordMessageRef = DiscordMessageRef(guid = guid, channelId = channelId, messageId = messageId)

    @BeforeEach
    fun setUp() {
        val container = SharedPostgresContainer.instance
        val dataSource =
            PGSimpleDataSource().apply {
                setUrl(container.jdbcUrl)
                user = container.username
                password = container.password
            }
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().execute("TRUNCATE TABLE notify_discord_message")
        }
        kueryClient = SpringJdbcKueryClient.builder().dataSource(dataSource).build()
    }

    @Test
    fun record_then_messages_since_returns_them() {
        val repository = repositoryAt(now)

        repository.record(listOf(message("g1", "m1"), message("g2", "m2", channelId = "ch-2")))

        assertEquals(
            listOf(message("g1", "m1"), message("g2", "m2", channelId = "ch-2")),
            repository.messagesSince(now.minus(Duration.ofDays(1))).sortedBy { it.messageId },
        )
    }

    @Test
    fun messages_since_returns_empty_list_when_none_recorded() {
        val repository = repositoryAt(now)

        assertEquals(emptyList(), repository.messagesSince(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun messages_since_excludes_rows_older_than_since() {
        repositoryAt(now.minus(Duration.ofHours(48))).record(listOf(message("old", "m-old")))
        repositoryAt(now.minus(Duration.ofHours(1))).record(listOf(message("recent", "m-recent")))

        val messages = repositoryAt(now).messagesSince(now.minus(Duration.ofHours(24)))

        assertEquals(listOf(message("recent", "m-recent")), messages)
    }

    @Test
    fun messages_since_includes_row_exactly_at_since_boundary() {
        val since = now.minus(Duration.ofHours(24))
        repositoryAt(since).record(listOf(message("at-boundary", "m-at")))
        repositoryAt(since.minus(Duration.ofMillis(1))).record(listOf(message("just-before", "m-before")))

        val messages = repositoryAt(now).messagesSince(since)

        assertEquals(listOf(message("at-boundary", "m-at")), messages)
    }

    @Test
    fun record_is_idempotent_for_the_same_message_id() {
        val repository = repositoryAt(now)
        repository.record(listOf(message("g1", "m1")))

        repository.record(listOf(message("g1", "m1")))

        assertEquals(listOf(message("g1", "m1")), repository.messagesSince(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun record_does_nothing_for_empty_list() {
        val repository = repositoryAt(now)

        repository.record(emptyList())

        assertEquals(emptyList(), repository.messagesSince(now.minus(Duration.ofDays(1))))
    }
}
