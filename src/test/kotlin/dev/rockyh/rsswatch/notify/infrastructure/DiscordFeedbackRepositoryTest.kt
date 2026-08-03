package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.spring.jdbc.SpringJdbcKueryClient
import dev.rockyh.rsswatch.notify.domain.DiscordReply
import dev.rockyh.rsswatch.notify.domain.ReactionCount
import dev.rockyh.rsswatch.testing.SharedPostgresContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

class DiscordFeedbackRepositoryTest {

    private lateinit var kueryClient: KueryBlockingClient

    private val now: Instant = Instant.parse("2026-08-04T08:00:00Z")

    private fun repositoryAt(instant: Instant): DiscordFeedbackRepository =
        DiscordFeedbackRepository(kueryClient, Clock.fixed(instant, ZoneOffset.UTC))

    private fun reply(
        replyMessageId: String,
        referencedMessageId: String = "m1",
        content: String = "あとで見る",
        repliedAt: Instant = Instant.parse("2026-08-04T09:00:00Z"),
    ): DiscordReply =
        DiscordReply(
            replyMessageId = replyMessageId,
            referencedMessageId = referencedMessageId,
            authorId = "user-1",
            authorName = "rocky",
            content = content,
            repliedAt = repliedAt,
        )

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
            connection.createStatement().execute("TRUNCATE TABLE notify_reaction, notify_reply")
        }
        kueryClient = SpringJdbcKueryClient.builder().dataSource(dataSource).build()
    }

    @Test
    fun replace_reactions_then_reactions_returns_the_snapshot() {
        val repository = repositoryAt(now)

        repository.replaceReactions("m1", listOf(ReactionCount("👍", 2), ReactionCount("👎", 1)))

        assertEquals(
            listOf(ReactionCount("👍", 2), ReactionCount("👎", 1)).sortedBy { it.emoji },
            repository.reactions("m1").sortedBy { it.emoji },
        )
    }

    @Test
    fun reactions_returns_empty_list_when_none_recorded() {
        assertEquals(emptyList(), repositoryAt(now).reactions("m1"))
    }

    @Test
    fun replace_reactions_overwrites_counts_and_removes_missing_emojis() {
        // スナップショット置き換え: 前回あった絵文字が今回の取得結果に無ければ「取り消された」ので消す
        val repository = repositoryAt(now)
        repository.replaceReactions("m1", listOf(ReactionCount("👍", 2), ReactionCount("👎", 1)))

        repository.replaceReactions("m1", listOf(ReactionCount("👍", 3)))

        assertEquals(listOf(ReactionCount("👍", 3)), repository.reactions("m1"))
    }

    @Test
    fun replace_reactions_with_empty_list_clears_previous_reactions() {
        val repository = repositoryAt(now)
        repository.replaceReactions("m1", listOf(ReactionCount("👍", 2)))

        repository.replaceReactions("m1", emptyList())

        assertEquals(emptyList(), repository.reactions("m1"))
    }

    @Test
    fun replace_reactions_keeps_other_messages_untouched() {
        val repository = repositoryAt(now)
        repository.replaceReactions("m1", listOf(ReactionCount("👍", 2)))
        repository.replaceReactions("m2", listOf(ReactionCount("🎉", 1)))

        repository.replaceReactions("m1", emptyList())

        assertEquals(listOf(ReactionCount("🎉", 1)), repository.reactions("m2"))
    }

    @Test
    fun record_replies_then_replies_to_returns_them() {
        val repository = repositoryAt(now)

        repository.recordReplies(listOf(reply("r1"), reply("r2", content = "面白かった")))

        assertEquals(
            listOf(reply("r1"), reply("r2", content = "面白かった")),
            repository.repliesTo("m1").sortedBy { it.replyMessageId },
        )
    }

    @Test
    fun replies_to_returns_only_replies_for_the_given_message() {
        val repository = repositoryAt(now)
        repository.recordReplies(listOf(reply("r1"), reply("r2", referencedMessageId = "m2")))

        assertEquals(listOf(reply("r1")), repository.repliesTo("m1"))
    }

    @Test
    fun record_replies_upserts_content_for_the_same_reply_message_id() {
        // 返信本文の編集に追従する(reply_message_id が同じなら上書き)
        val repository = repositoryAt(now)
        repository.recordReplies(listOf(reply("r1", content = "あとで見る")))

        repository.recordReplies(listOf(reply("r1", content = "読んだ、良かった")))

        assertEquals(listOf(reply("r1", content = "読んだ、良かった")), repository.repliesTo("m1"))
    }

    @Test
    fun record_replies_does_nothing_for_empty_list() {
        val repository = repositoryAt(now)

        repository.recordReplies(emptyList())

        assertEquals(emptyList(), repository.repliesTo("m1"))
    }
}
