package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.core.list
import dev.rockyh.rsswatch.notify.ConditionalOnFeedbackEnabled
import dev.rockyh.rsswatch.notify.domain.DiscordReply
import dev.rockyh.rsswatch.notify.domain.FeedbackStore
import dev.rockyh.rsswatch.notify.domain.ReactionCount
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Discord 上のフィードバック(リアクション・返信)の生データ保存。方言依存(ON CONFLICT)を
 * このクラスに閉じ込める。スキーマは Flyway(V4__notify_discord_feedback.sql)が唯一の正本。
 *
 * - タイムスタンプは TIMESTAMPTZ。[Instant] は UTC の [OffsetDateTime] に変換してバインドする
 *   (PostedGuidRepository と同じ作法)
 * - [replaceReactions] は DELETE → INSERT の全置き換え(1 メッセージあたり数行なので差分更新にしない)
 * - [recordReplies] は `ON CONFLICT (reply_message_id) DO UPDATE` で本文編集に追従する
 */
@Repository
@ConditionalOnFeedbackEnabled
class DiscordFeedbackRepository(
    private val kueryClient: KueryBlockingClient,
    private val clock: Clock = Clock.systemUTC(),
) : FeedbackStore {

    @Transactional
    override fun replaceReactions(messageId: String, reactions: List<ReactionCount>) {
        val updatedAt = toUtcOffset(clock.instant())
        kueryClient
            .sql {
                +"DELETE FROM notify_reaction WHERE message_id = $messageId"
            }.rowsUpdated()
        for (reaction in reactions) {
            kueryClient
                .sql {
                    +"""
                    INSERT INTO notify_reaction (message_id, emoji, reaction_count, updated_at)
                    VALUES ($messageId, ${reaction.emoji}, ${reaction.count}, $updatedAt)
                    """
                }.rowsUpdated()
        }
    }

    override fun reactions(messageId: String): List<ReactionCount> =
        kueryClient
            .sql {
                +"SELECT emoji, reaction_count FROM notify_reaction WHERE message_id = $messageId ORDER BY emoji ASC"
            }.list<ReactionRow>()
            .map { ReactionCount(emoji = it.emoji, count = it.reactionCount) }

    @Transactional
    override fun recordReplies(replies: List<DiscordReply>) {
        val fetchedAt = toUtcOffset(clock.instant())
        for (reply in replies) {
            val repliedAt = toUtcOffset(reply.repliedAt)
            kueryClient
                .sql {
                    +"""
                    INSERT INTO notify_reply (reply_message_id, message_id, author_id, author_name, content, replied_at, fetched_at)
                    VALUES (${reply.replyMessageId}, ${reply.referencedMessageId}, ${reply.authorId}, ${reply.authorName},
                            ${reply.content}, $repliedAt, $fetchedAt)
                    ON CONFLICT (reply_message_id) DO UPDATE
                    SET content = EXCLUDED.content, fetched_at = EXCLUDED.fetched_at
                    """
                }.rowsUpdated()
        }
    }

    override fun repliesTo(messageId: String): List<DiscordReply> =
        kueryClient
            .sql {
                +"""
                SELECT reply_message_id, message_id, author_id, author_name, content, replied_at
                FROM notify_reply WHERE message_id = $messageId ORDER BY reply_message_id ASC
                """
            }.list<ReplyRow>()
            .map {
                DiscordReply(
                    replyMessageId = it.replyMessageId,
                    referencedMessageId = it.messageId,
                    authorId = it.authorId,
                    authorName = it.authorName,
                    content = it.content,
                    repliedAt = it.repliedAt.toInstant(),
                )
            }

    private fun toUtcOffset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private data class ReactionRow(val emoji: String, val reactionCount: Int)

    private data class ReplyRow(
        val replyMessageId: String,
        val messageId: String,
        val authorId: String,
        val authorName: String,
        val content: String,
        val repliedAt: OffsetDateTime,
    )
}
