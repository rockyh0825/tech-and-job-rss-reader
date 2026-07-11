package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.TechDigest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * 「求人で言及された技術 × その技術の記事」を embed 形式で 1 通にまとめて Discord Webhook へ POST する(infrastructure)。
 *
 * 1 記事 = 1 embed。embed の author に技術名(求人言及数)、title に記事タイトル+URL、field「要約」に AI 要約を載せる。
 * 末尾にサイト一覧への導線として CTA embed([siteUrl])を必ず添える。
 *
 * 429(レート制限)は `Retry-After` を尊重して [maxRetries] 回まで限定リトライし、上限到達で [Result.failure]。
 * それ以外のエラーはリトライせず失敗を返す。Discord の上限は 10 embed/通・合計 6000 文字。
 */
@Component
@ConditionalOnNotifyEnabled
class DiscordWebhookClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.discord-webhook-url:}") private val webhookUrl: String,
    @Value("\${rss-watch.notify.site-url:https://rss-watch.rocky-ha.com/}") private val siteUrl: String,
    @Value("\${rss-watch.notify.discord.max-retries:2}") private val maxRetries: Int,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxTotalEmbedChars: Int = DEFAULT_MAX_TOTAL_EMBED_CHARS,
) : DigestPublisher {

    private val restClient: RestClient = restClientBuilder.build()

    override fun post(digests: List<TechDigest>): Result<Unit> {
        if (webhookUrl.isBlank()) {
            log.warn("Discord Webhook URL が空のため投稿をスキップします。RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL を設定してください。")
            return Result.failure(IllegalStateException("Discord Webhook URL が設定されていません(空文字)"))
        }
        val cta = ctaEmbed()
        // 末尾 CTA embed のぶん(1 件・文字数)を必ず残すため、記事 embed は MAX_EMBEDS-1 件・
        // 合計上限から CTA 文字数を差し引いた範囲に収める。
        val articleEmbeds =
            fitWithinTotalLimit(
                embeds = digests.toArticleEmbeds().take(MAX_EMBEDS - 1),
                reservedChars = cta.characterCount(),
            )
        val payload = WebhookPayload(embeds = articleEmbeds + cta)
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
     *
     * RFC 7231 上 `Retry-After` は HTTP-date 形式も取り得るが、Discord は常に数値秒を返す。
     * よって数値秒(`toDoubleOrNull`)のみ対応し、date 形式ならボディ→既定値へフォールバックする。
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

    /** 技術グループを平坦化し、記事 1 件ごとに 1 embed へ変換する(投稿順は技術ランキング順・記事の新しい順)。 */
    private fun List<TechDigest>.toArticleEmbeds(): List<Embed> =
        flatMap { digest -> digest.articles.map { article -> toEmbed(digest, article) } }

    /**
     * embed を先頭から累積し、合計文字数([reservedChars] を既に消費済みとして加算)が [maxTotalEmbedChars] を
     * 超えない範囲だけ残す。[reservedChars] は末尾に必ず付ける CTA embed のぶんを先に確保するためのもの。
     *
     * 単一 embed は [toEmbed] でクランプ済みのため上限を大きく超えず、通常このループの先頭で break することはない。
     * ただし将来クランプ定数を引き上げた場合に静かに空ペイロード=400 を送らないよう、入力が非空なのに 1 件も
     * 収まらなかったときは先頭 1 件だけは必ず残すフォールバックを置く(CTA は呼び出し側で必ず後置される)。
     */
    private fun fitWithinTotalLimit(embeds: List<Embed>, reservedChars: Int): List<Embed> {
        val fitted = mutableListOf<Embed>()
        var total = reservedChars
        for (embed in embeds) {
            val chars = embed.characterCount()
            if (total + chars > maxTotalEmbedChars) break
            fitted += embed
            total += chars
        }
        if (fitted.isEmpty() && embeds.isNotEmpty()) return listOf(embeds.first())
        return fitted
    }

    /**
     * Discord が合計 6000 文字上限で数える対象(author name + title + description + 各 field name/value)。
     * 文字数は UTF-16 code unit(`.length`)で数える。Discord の上限は本来 Unicode コードポイント基準だが、
     * code unit 計数は常にコードポイント数以上になるため保守的(安全側)であり、400 を招かない
     * (絵文字主体のテキストでは必要以上に切る可能性はある)。これは意図的な選択。
     */
    private fun Embed.characterCount(): Int =
        (author?.name?.length ?: 0) +
            title.length +
            (description?.length ?: 0) +
            (fields?.sumOf { it.name.length + it.value.length } ?: 0)

    /**
     * 記事 1 件を embed に変換する。author に技術グループの見出し(技術名 + 求人言及数)、field は見出しを
     * 「要約」に固定して AI 要約本文を載せる(要約なしは field ごと省く)。
     */
    private fun toEmbed(digest: TechDigest, article: DigestArticle): Embed =
        Embed(
            author = EmbedAuthor(name = authorLabel(digest).clampTo(MAX_AUTHOR_NAME_LENGTH)),
            title = article.title.clampTo(MAX_TITLE_LENGTH),
            url = article.url,
            description = null,
            // 要約が空白のみの場合も field ごと省く。Discord は field value 空(0 文字)を 400 で弾くため。
            fields =
                article.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(EmbedField(name = SUMMARY_FIELD_NAME, value = it.clampTo(MAX_FIELD_VALUE_LENGTH))) },
        )

    /** 技術グループの見出し文言(例: 「🧩 Kotlin ・ 求人 5 件で言及」)。 */
    private fun authorLabel(digest: TechDigest): String =
        "🧩 ${digest.keyword} ・ 求人 ${digest.mentionCount} 件で言及"

    /** 末尾に添えるサイト一覧への導線 embed。title をリンクにして [siteUrl] へ飛ばす。 */
    private fun ctaEmbed(): Embed =
        Embed(
            author = null,
            title = CTA_TITLE,
            url = siteUrl,
            description = null,
            fields = null,
        )

    /**
     * Discord の文字数上限を超える場合は末尾を省略記号で切り詰める。上限内はそのまま返す。
     * サロゲートペア(絵文字等)の途中で切って壊れた文字を残さないよう、境界直前が
     * high surrogate なら 1 code unit 手前で切る。
     *
     * 上限 [max] は UTF-16 code unit(`.length`)で判定する。[characterCount] と同じく
     * コードポイント基準より保守的(安全側)な意図的選択。
     */
    private fun String.clampTo(max: Int): String {
        if (length <= max) return this
        var end = max - ELLIPSIS.length
        if (end > 0 && this[end - 1].isHighSurrogate()) end--
        return substring(0, end) + ELLIPSIS
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class WebhookPayload(val embeds: List<Embed>)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class Embed(
        val author: EmbedAuthor?,
        val title: String,
        val url: String,
        val description: String?,
        val fields: List<EmbedField>?,
    )

    private data class EmbedAuthor(val name: String)

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
        private const val DEFAULT_MAX_TOTAL_EMBED_CHARS = 6000

        /** Discord embed の author name 最大文字数。 */
        private const val MAX_AUTHOR_NAME_LENGTH = 256

        /** Discord embed の title 最大文字数。 */
        private const val MAX_TITLE_LENGTH = 256

        /** Discord embed field の value 最大文字数。 */
        private const val MAX_FIELD_VALUE_LENGTH = 1024

        /** 要約 field の見出し(常にこの固定文言。モデル生成の見出しは使わない)。 */
        private const val SUMMARY_FIELD_NAME = "要約"

        /** 末尾 CTA embed のタイトル(サイトへの導線)。 */
        private const val CTA_TITLE = "🔗 求人で注目の技術と記事をサイトで見る"

        /** Retry-After ヘッダもボディも取れなかった 429 の既定待機(即リトライで叩き続けないため)。 */
        private const val DEFAULT_RETRY_MS = 1000L

        private const val ELLIPSIS = "…"
    }
}
