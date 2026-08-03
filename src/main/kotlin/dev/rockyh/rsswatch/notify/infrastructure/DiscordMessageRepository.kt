package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.core.list
import dev.rockyh.rsswatch.notify.ConditionalOnAnyNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestMessageStore
import dev.rockyh.rsswatch.notify.domain.DiscordMessageRef
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 投稿済みダイジェスト記事 ↔ Discord メッセージの対応の記録と照会(リアクション・返信の回収用)。
 * 方言依存(ON CONFLICT)をこのクラスに閉じ込める。
 * スキーマは Flyway(V4__notify_discord_feedback.sql)が唯一の正本。
 *
 * - posted_at は TIMESTAMPTZ。[Instant] は UTC の [OffsetDateTime] に変換してバインドする
 *   (PostedGuidRepository と同じ作法)
 * - [record] は `INSERT ... ON CONFLICT (message_id) DO NOTHING` で同メッセージの再投入を無害化する
 */
@Repository
@ConditionalOnAnyNotifyEnabled
class DiscordMessageRepository(
    private val kueryClient: KueryBlockingClient,
    private val clock: Clock = Clock.systemUTC(),
) : DigestMessageStore {

    @Transactional
    override fun record(messages: List<DiscordMessageRef>) {
        val postedAt = toUtcOffset(clock.instant())
        for (message in messages) {
            kueryClient
                .sql {
                    +"""
                    INSERT INTO notify_discord_message (message_id, channel_id, guid, posted_at)
                    VALUES (${message.messageId}, ${message.channelId}, ${message.guid}, $postedAt)
                    ON CONFLICT (message_id) DO NOTHING
                    """
                }.rowsUpdated()
        }
    }

    override fun messagesSince(since: Instant): List<DiscordMessageRef> {
        val cutoff = toUtcOffset(since)
        return kueryClient
            .sql {
                +"""
                SELECT guid, channel_id, message_id FROM notify_discord_message
                WHERE posted_at >= $cutoff
                ORDER BY message_id ASC
                """
            }.list<MessageRow>()
            .map { DiscordMessageRef(guid = it.guid, channelId = it.channelId, messageId = it.messageId) }
    }

    private fun toUtcOffset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private data class MessageRow(val guid: String, val channelId: String, val messageId: String)
}
