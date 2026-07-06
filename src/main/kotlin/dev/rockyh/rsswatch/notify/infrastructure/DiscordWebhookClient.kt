package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
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
        val embeds = fitWithinTotalLimit(entries.take(MAX_EMBEDS).map(::toEmbed))
        val payload = WebhookPayload(embeds = embeds)
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

    /**
     * 429 のときだけ待機ミリ秒を返す。それ以外は null(リトライしない)。
     * 待機時間は `Retry-After` ヘッダ(秒)→ JSON ボディの `retry_after`(秒)→ 既定値の順に採用する。
     */
    private fun retryAfterMsOrNull(error: Throwable): Long? {
        if (error !is HttpClientErrorException.TooManyRequests) return null
        val seconds =
            error.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)?.toDoubleOrNull()
                ?: bodyRetryAfterSeconds(error)
        return seconds?.let { (it * 1000).toLong() } ?: DEFAULT_RETRY_MS
    }

    /** 429 レスポンスボディ `{"retry_after": <秒>}` を読み取る(取得・解析失敗は null)。 */
    private fun bodyRetryAfterSeconds(error: HttpClientErrorException): Double? =
        runCatching { error.getResponseBodyAs(RateLimitBody::class.java)?.retryAfter }.getOrNull()

    /** embed を先頭から累積し、合計文字数が [MAX_TOTAL_EMBED_CHARS] を超えない範囲だけ残す。 */
    private fun fitWithinTotalLimit(embeds: List<Embed>): List<Embed> {
        val fitted = mutableListOf<Embed>()
        var total = 0
        for (embed in embeds) {
            val chars = embed.characterCount()
            if (total + chars > MAX_TOTAL_EMBED_CHARS) break
            fitted += embed
            total += chars
        }
        return fitted
    }

    /** Discord が合計 6000 文字上限で数える対象(title + description + 各 field name/value)。 */
    private fun Embed.characterCount(): Int =
        title.length +
            (description?.length ?: 0) +
            (fields?.sumOf { it.name.length + it.value.length } ?: 0)

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

    /**
     * Discord の文字数上限を超える場合は末尾を省略記号で切り詰める。上限内はそのまま返す。
     * サロゲートペア(絵文字等)の途中で切って壊れた文字を残さないよう、境界直前が
     * high surrogate なら 1 code unit 手前で切る。
     */
    private fun String.clampTo(max: Int): String {
        if (length <= max) return this
        var end = max - ELLIPSIS.length
        if (end > 0 && this[end - 1].isHighSurrogate()) end--
        return substring(0, end) + ELLIPSIS
    }

    private data class WebhookPayload(val embeds: List<Embed>)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class Embed(
        val title: String,
        val url: String,
        val description: String?,
        val fields: List<EmbedField>?,
    )

    private data class EmbedField(val name: String, val value: String)

    /** 429 レスポンスボディ(必要なフィールドのみ)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RateLimitBody(
        @param:JsonProperty("retry_after") val retryAfter: Double?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(DiscordWebhookClient::class.java)

        /** Discord が 1 通の Webhook で受け付ける embed の最大数。 */
        private const val MAX_EMBEDS = 10

        /** Discord が 1 通の全 embed 合計で受け付ける最大文字数(超過で 400)。 */
        private const val MAX_TOTAL_EMBED_CHARS = 6000

        /** Discord embed の title 最大文字数。 */
        private const val MAX_TITLE_LENGTH = 256

        /** Discord embed の description 最大文字数。 */
        private const val MAX_DESCRIPTION_LENGTH = 4096

        /** Discord embed field の value 最大文字数。 */
        private const val MAX_FIELD_VALUE_LENGTH = 1024

        /** Retry-After ヘッダもボディも取れなかった 429 の既定待機(即リトライで叩き続けないため)。 */
        private const val DEFAULT_RETRY_MS = 1000L

        private const val ELLIPSIS = "…"
    }
}
