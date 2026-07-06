package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestEntry
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
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
        val payload = WebhookPayload(embeds = entries.map(::toEmbed))
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
            title = entry.title,
            url = entry.url,
            description = entry.summary,
            fields =
                entry.keywords
                    .takeIf { it.isNotEmpty() }
                    ?.let { listOf(EmbedField(name = "キーワード", value = it.joinToString(", "))) },
        )

    private data class WebhookPayload(val embeds: List<Embed>)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class Embed(
        val title: String,
        val url: String,
        val description: String?,
        val fields: List<EmbedField>?,
    )

    private data class EmbedField(val name: String, val value: String)
}
