package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.spring.jdbc.SpringJdbcKueryClient
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

class PostedGuidRepositoryTest {

    private lateinit var kueryClient: KueryBlockingClient

    private val now: Instant = Instant.parse("2026-07-06T08:00:00Z")

    private fun repositoryAt(instant: Instant): PostedGuidRepository =
        PostedGuidRepository(kueryClient, Clock.fixed(instant, ZoneOffset.UTC))

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
            connection.createStatement().execute("TRUNCATE TABLE notify_posted")
        }
        kueryClient = SpringJdbcKueryClient.builder().dataSource(dataSource).build()
    }

    @Test
    fun mark_posted_then_posted_guids_returns_them() {
        val repository = repositoryAt(now)

        repository.markPosted(listOf("a", "b"))

        assertEquals(setOf("a", "b"), repository.postedGuids(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun posted_guids_returns_empty_set_when_none_recorded() {
        val repository = repositoryAt(now)

        assertEquals(emptySet(), repository.postedGuids(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun mark_posted_is_idempotent_for_the_same_guid() {
        val repository = repositoryAt(now)
        repository.markPosted(listOf("a"))

        repository.markPosted(listOf("a"))

        assertEquals(setOf("a"), repository.postedGuids(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun mark_posted_keeps_earliest_posted_at_when_same_guid_reposted() {
        // ON CONFLICT DO NOTHING のため、再 mark しても posted_at は最初の値のまま(上書きしない)
        repositoryAt(now.minus(Duration.ofDays(2))).markPosted(listOf("a"))

        repositoryAt(now).markPosted(listOf("a"))

        // 最初の posted_at(2 日前)より後の since では既に窓の外に落ちている
        val repository = repositoryAt(now)
        assertEquals(emptySet(), repository.postedGuids(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun mark_posted_does_nothing_for_empty_list() {
        val repository = repositoryAt(now)

        repository.markPosted(emptyList())

        assertEquals(emptySet(), repository.postedGuids(now.minus(Duration.ofDays(1))))
    }

    @Test
    fun posted_guids_excludes_rows_older_than_since() {
        repositoryAt(now.minus(Duration.ofHours(48))).markPosted(listOf("old"))
        repositoryAt(now.minus(Duration.ofHours(1))).markPosted(listOf("recent"))

        val posted = repositoryAt(now).postedGuids(now.minus(Duration.ofHours(24)))

        assertEquals(setOf("recent"), posted)
    }

    @Test
    fun posted_guids_includes_row_exactly_at_since_boundary() {
        val since = now.minus(Duration.ofHours(24))
        repositoryAt(since).markPosted(listOf("at-boundary"))
        repositoryAt(since.minus(Duration.ofMillis(1))).markPosted(listOf("just-before"))

        val posted = repositoryAt(now).postedGuids(since)

        assertEquals(setOf("at-boundary"), posted)
    }
}
