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

class FeaturedTechRepositoryTest {

    private lateinit var kueryClient: KueryBlockingClient

    private val now: Instant = Instant.parse("2026-07-13T08:00:00Z")

    private fun repositoryAt(instant: Instant): FeaturedTechRepository =
        FeaturedTechRepository(kueryClient, Clock.fixed(instant, ZoneOffset.UTC))

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
            connection.createStatement().execute("TRUNCATE TABLE notify_featured_techs")
        }
        kueryClient = SpringJdbcKueryClient.builder().dataSource(dataSource).build()
    }

    @Test
    fun mark_featured_then_last_featured_at_returns_them() {
        val repository = repositoryAt(now)

        repository.markFeatured(listOf("Kotlin", "AWS"))

        assertEquals(mapOf("Kotlin" to now, "AWS" to now), repository.lastFeaturedAt())
    }

    @Test
    fun mark_featured_updates_timestamp_for_existing_keyword() {
        // notify_posted(DO NOTHING)と違い、再 mark で last_featured_at を最新時刻へ上書きする
        val before = now.minus(Duration.ofDays(3))
        repositoryAt(before).markFeatured(listOf("Kotlin"))

        repositoryAt(now).markFeatured(listOf("Kotlin"))

        assertEquals(mapOf("Kotlin" to now), repositoryAt(now).lastFeaturedAt())
    }

    @Test
    fun last_featured_at_returns_empty_map_when_none_recorded() {
        assertEquals(emptyMap(), repositoryAt(now).lastFeaturedAt())
    }

    @Test
    fun mark_featured_does_nothing_for_empty_list() {
        val repository = repositoryAt(now)

        repository.markFeatured(emptyList())

        assertEquals(emptyMap(), repository.lastFeaturedAt())
    }
}
