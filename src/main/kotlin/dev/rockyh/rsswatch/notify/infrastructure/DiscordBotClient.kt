package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.rockyh.rsswatch.notify.ConditionalOnFeedbackEnabled
import dev.rockyh.rsswatch.notify.domain.DiscordFeedbackSource
import dev.rockyh.rsswatch.notify.domain.DiscordReply
import dev.rockyh.rsswatch.notify.domain.ReactionCount
import java.time.OffsetDateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * Discord Bot API(REST)でダイジェスト投稿のリアクション・返信を読み取る(infrastructure)。
 *
 * Webhook(送信専用)と違い、読み取りには Bot トークンが要る。Bot は対象サーバーに
 * View Channel + Read Message History 権限で招待されている前提(手順は docs/notify.md 参照)。
 * 返信本文まで読むには Developer Portal で Message Content Intent を有効にする(無効だと content が
 * 空文字で届く。その場合も「返信が付いた事実」は取れる)。
 *
 * リトライは持たない。回収は定期ジョブでやり直しが効くため、失敗はそのまま伝播させて
 * 呼び出し側(CollectFeedbackUseCase)が warn ログで済ませる。
 */
@Component
@ConditionalOnFeedbackEnabled
class DiscordBotClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.feedback.bot-token:}") private val botToken: String,
    @Value("\${rss-watch.notify.feedback.api-base-url:https://discord.com/api/v10}") private val apiBaseUrl: String,
) : DiscordFeedbackSource {

    init {
        // @ConditionalOnProperty は空文字でも真になる。空トークンのまま 6 時間おきに 401 を吐き続けるより
        // 起動時に fail fast して設定ミスに気づけるようにする
        require(botToken.isNotBlank()) { "rss-watch.notify.feedback.bot-token must not be blank" }
    }

    private val restClient: RestClient = restClientBuilder.build()

    override fun reactions(channelId: String, messageId: String): List<ReactionCount>? {
        val message =
            try {
                restClient
                    .get()
                    .uri("$apiBaseUrl/channels/$channelId/messages/$messageId")
                    .header(HttpHeaders.AUTHORIZATION, "Bot $botToken")
                    .retrieve()
                    .body(MessageBody::class.java)
            } catch (e: HttpClientErrorException.NotFound) {
                // メッセージが削除済み。0 件(空リスト)と区別して null を返す
                return null
            }
        // リアクション 0 件のとき Discord は reactions フィールド自体を返さない
        return message?.reactions.orEmpty().mapNotNull { it.toReactionCount() }
    }

    override fun repliesTo(channelId: String, messageIds: Set<String>): List<DiscordReply> {
        val messages =
            restClient
                .get()
                .uri("$apiBaseUrl/channels/$channelId/messages?limit=$MESSAGES_FETCH_LIMIT")
                .header(HttpHeaders.AUTHORIZATION, "Bot $botToken")
                .retrieve()
                .body(Array<MessageBody>::class.java)
                .orEmpty()
        return messages.mapNotNull { it.toReplyTo(messageIds) }
    }

    /** 429 レスポンス等の transport 詳細はここでは扱わない(kdoc 参照)。 */
    private fun ReactionBody.toReactionCount(): ReactionCount? {
        val name = emoji?.name ?: return null
        // カスタム絵文字は名前だけだと別サーバーの同名絵文字と衝突するので name:id で識別する
        val key = if (emoji.id == null) name else "$name:${emoji.id}"
        return ReactionCount(emoji = key, count = count)
    }

    private fun MessageBody.toReplyTo(messageIds: Set<String>): DiscordReply? {
        val referenced = messageReference?.messageId ?: return null
        if (referenced !in messageIds) return null
        val author = author ?: return null
        // bot(他の bot や webhook)による返信は人のフィードバックではないので拾わない
        if (author.bot == true) return null
        val repliedAt = timestamp?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
        return DiscordReply(
            replyMessageId = id,
            referencedMessageId = referenced,
            authorId = author.id,
            authorName = author.username,
            // Message Content Intent が無効だと content は届かない。返信が付いた事実だけでも記録する
            content = content.orEmpty(),
            repliedAt = repliedAt,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessageBody(
        val id: String,
        val content: String?,
        val timestamp: String?,
        val author: AuthorBody?,
        val reactions: List<ReactionBody>?,
        @param:JsonProperty("message_reference") val messageReference: MessageReferenceBody?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AuthorBody(
        val id: String,
        val username: String,
        val bot: Boolean?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ReactionBody(
        val emoji: EmojiBody?,
        val count: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class EmojiBody(
        val id: String?,
        val name: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessageReferenceBody(
        @param:JsonProperty("message_id") val messageId: String?,
    )

    companion object {
        /** Get Channel Messages の 1 リクエスト上限(これより古い返信は拾えない)。 */
        private const val MESSAGES_FETCH_LIMIT = 100
    }
}
