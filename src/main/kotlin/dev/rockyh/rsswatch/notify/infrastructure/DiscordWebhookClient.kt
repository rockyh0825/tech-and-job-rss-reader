package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestEntry
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * N 件を embed 形式(タイトル・URL・要約・キーワード)で 1 通にまとめて Discord Webhook へ POST する(infrastructure)。
 *
 * 429(レート制限)は `Retry-After` を尊重して [maxRetries] 回まで限定リトライし、上限到達で [Result.failure]。
 * それ以外のエラーはリトライせず失敗を返す(要件 3.2)。Discord の上限は 10 embed/通。
 */
@Component
@ConditionalOnNotifyEnabled
class DiscordWebhookClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.discord-webhook-url:}") private val webhookUrl: String,
    @Value("\${rss-watch.notify.discord.max-retries:2}") private val maxRetries: Int,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : DigestPublisher {

    private val restClient: RestClient = restClientBuilder.build()

    override fun post(entries: List<DigestEntry>): Result<Unit> {
        if (webhookUrl.isBlank()) {
            log.warn("Discord Webhook URL が空のため投稿をスキップします。RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL を設定してください。")
            return Result.failure(IllegalStateException("Discord Webhook URL が設定されていません(空文字)"))
        }
        val payload = WebhookPayload(embeds = entries.take(MAX_EMBEDS).map(::toEmbed))
        var attempt = 0
        while (true) {
            val outcome = runCatching { send(payload) }
            outcome.onSuccess { return Result.success(Unit) }
            val error = outcome.exceptionOrNull()!!
            val retryAfterMs = retryAfterMsOrNull(error)
            if (retryAfterMs == null || attempt >= maxRetries) {
                return Result.failure(error)
            }
            sleeper(retryAfterMs)
            attempt++
        }
    }

    private fun send(payload: WebhookPayload) {
        restClient
            .post()
            .uri(webhookUrl)
            .header("content-type", "application/json")
            .body(payload)
            .retrieve()
            .toBodilessEntity()
    }

    /** 429 のときだけ Retry-After(秒)をミリ秒に変換して返す。それ以外は null(リトライしない)。 */
    private fun retryAfterMsOrNull(error: Throwable): Long? {
        if (error !is HttpClientErrorException.TooManyRequests) return null
        val seconds = error.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)?.toDoubleOrNull() ?: 0.0
        return (seconds * 1000).toLong()
    }

    private fun toEmbed(entry: DigestEntry): Embed =
        Embed(
            title = entry.title.clampTo(MAX_TITLE_LENGTH),
            url = entry.url,
            description = entry.summary?.clampTo(MAX_DESCRIPTION_LENGTH),
            fields =
                entry.keywords
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        listOf(
                            EmbedField(name = "キーワード", value = it.joinToString(", ").clampTo(MAX_FIELD_VALUE_LENGTH)),
                        )
                    },
        )

    /** Discord の文字数上限を超える場合は末尾を省略記号で切り詰める。上限内はそのまま返す。 */
    private fun String.clampTo(max: Int): String =
        if (length <= max) this else take(max - ELLIPSIS.length) + ELLIPSIS

    private data class WebhookPayload(val embeds: List<Embed>)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class Embed(
        val title: String,
        val url: String,
        val description: String?,
        val fields: List<EmbedField>?,
    )

    private data class EmbedField(val name: String, val value: String)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordWebhookClient::class.java)

        /** Discord が 1 通の Webhook で受け付ける embed の最大数。 */
        private const val MAX_EMBEDS = 10

        /** Discord embed の title 最大文字数。 */
        private const val MAX_TITLE_LENGTH = 256

        /** Discord embed の description 最大文字数。 */
        private const val MAX_DESCRIPTION_LENGTH = 4096

        /** Discord embed field の value 最大文字数。 */
        private const val MAX_FIELD_VALUE_LENGTH = 1024

        private const val ELLIPSIS = "…"
    }
}
