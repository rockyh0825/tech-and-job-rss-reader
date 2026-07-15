package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import dev.rockyh.rsswatch.notify.domain.TechDigest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * 「求人で言及された技術 × その技術の記事」を Discord Webhook へ POST する(infrastructure)。
 *
 * **記事 1 件 = 1 通**(embed 1 つ)。embed の author に技術名(求人言及数)、title に記事タイトル+URL、
 * field「要約」に AI 要約を載せる。記事を全部投稿できたら、最後にサイト一覧への導線([siteUrl])を
 * 単独の 1 通として送る。
 *
 * 1 通にまとめず記事ごとに分けているため、Discord 側の「10 embed/通・合計 6000 文字/通」の上限に
 * 記事数が縛られない(単一 embed の title 256・field value 1024 の上限は [clampTo] で守る)。
 * 代わりに投稿は途中で失敗し得るので、どこまで投稿できたかを [PostOutcome] で呼び出し側へ返す。
 *
 * 429(レート制限)は `Retry-After` を尊重して [maxRetries] 回まで限定リトライする。リトライ回数は
 * **1 通ごと**に数え直すため、ある記事での 429 が後続の記事のリトライ余力を削らない。
 * それ以外のエラーはリトライしない。
 */
@Component
@ConditionalOnNotifyEnabled
class DiscordWebhookClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.discord-webhook-url:}") private val webhookUrl: String,
    @Value("\${rss-watch.notify.site-url:https://rss-watch.rocky-ha.com/}") private val siteUrl: String,
    @Value("\${rss-watch.notify.discord.max-retries:2}") private val maxRetries: Int,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : DigestPublisher {

    private val restClient: RestClient = restClientBuilder.build()

    override fun post(digests: List<TechDigest>): PostOutcome {
        if (webhookUrl.isBlank()) {
            log.warn("Discord Webhook URL が空のため投稿をスキップします。RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL を設定してください。")
            return PostOutcome(emptyList(), IllegalStateException("Discord Webhook URL が設定されていません(空文字)"))
        }

        val postedGuids = mutableListOf<String>()
        for ((digest, article) in digests.toArticlePosts()) {
            val failure = sendWithRetry(toEmbed(digest, article)).exceptionOrNull()
            if (failure != null) {
                // 叩き続けても同じ結果になりやすいので以降は送らない。未投稿ぶんは次回の巡回で再度候補に上がる。
                log.warn("記事の投稿に失敗したため以降の投稿を中断します: {}", article.url, failure)
                return PostOutcome(postedGuids, failure)
            }
            postedGuids += article.guid
        }

        // 記事が 1 件も無ければ導線だけ送っても意味がない。
        if (postedGuids.isEmpty()) return PostOutcome(emptyList())

        // 記事を全部投稿できたときだけ、最後にサイトへの導線を送る。
        sendWithRetry(ctaEmbed()).onFailure { error ->
            // 記事自体は届いているため通知済みとして扱う(ここで失敗を返すと翌日これらを重複投稿してしまう)。
            log.warn("記事は全件投稿できましたが、サイト導線の投稿に失敗しました", error)
        }
        return PostOutcome(postedGuids)
    }

    /**
     * embed 1 つを 1 通として送る。429 のときだけ `Retry-After` に従って [maxRetries] 回まで再試行する。
     * 再試行回数はこの呼び出し内で数えるため、1 通ごとに満額の余力が与えられる。
     */
    private fun sendWithRetry(embed: Embed): Result<Unit> {
        val payload = WebhookPayload(embeds = listOf(embed))
        var attempt = 0
        while (true) {
            val outcome = runCatching { send(payload) }
            if (outcome.isSuccess) return Result.success(Unit)
            val error = outcome.exceptionOrNull()!!
            val retryAfterMs = retryAfterMsOrNull(error)
            if (retryAfterMs == null || attempt >= maxRetries) return Result.failure(error)
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

    /**
     * 技術グループを平坦化し、投稿 1 通ぶんの単位(所属技術と記事の組)に並べ直す。
     * 順序は技術ランキング順・記事の新しい順で、そのまま投稿順になる。
     */
    private fun List<TechDigest>.toArticlePosts(): List<Pair<TechDigest, DigestArticle>> =
        flatMap { digest -> digest.articles.map { article -> digest to article } }

    /**
     * 記事 1 件を embed に変換する。author に技術グループの見出し(技術名 + 求人言及数)、field は見出しを
     * 「要約」に固定して AI 要約本文を載せる(要約なしは field ごと省く)。記事の OGP 画像は
     * thumbnail(右上の小さい画像)に載せる(解決できていなければ省く)。
     */
    private fun toEmbed(digest: TechDigest, article: DigestArticle): Embed =
        Embed(
            author = EmbedAuthor(name = authorLabel(digest).clampTo(MAX_AUTHOR_NAME_LENGTH)),
            title = article.title.clampTo(MAX_TITLE_LENGTH),
            url = article.url,
            description = null,
            thumbnail = article.thumbnailUrl?.let { EmbedThumbnail(url = it) },
            // 要約が空白のみの場合も field ごと省く。Discord は field value 空(0 文字)を 400 で弾くため。
            fields =
                article.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(EmbedField(name = SUMMARY_FIELD_NAME, value = it.clampTo(MAX_FIELD_VALUE_LENGTH))) },
        )

    /** 技術グループの見出し文言(例: 「🧩 Kotlin ・ 求人 5 件で言及」)。 */
    private fun authorLabel(digest: TechDigest): String =
        "🧩 ${digest.keyword} ・ 求人 ${digest.mentionCount} 件で言及"

    /** 最後に単独で送る、サイト一覧への導線 embed。title をリンクにして [siteUrl] へ飛ばす。 */
    private fun ctaEmbed(): Embed =
        Embed(
            author = null,
            title = CTA_TITLE,
            url = siteUrl,
            description = null,
            thumbnail = null,
            fields = null,
        )

    /**
     * Discord の文字数上限を超える場合は末尾を省略記号で切り詰める。上限内はそのまま返す。
     * サロゲートペア(絵文字等)の途中で切って壊れた文字を残さないよう、境界直前が
     * high surrogate なら 1 code unit 手前で切る。
     *
     * 上限 [max] は UTF-16 code unit(`.length`)で判定する。Discord の上限は本来 Unicode コードポイント
     * 基準だが、code unit 計数は常にコードポイント数以上になるため保守的(安全側)であり、400 を招かない
     * (絵文字主体のテキストでは必要以上に切る可能性はある)。これは意図的な選択。
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
        val thumbnail: EmbedThumbnail?,
        val fields: List<EmbedField>?,
    )

    private data class EmbedAuthor(val name: String)

    /** embed 右上に小さく出る画像(Discord の `thumbnail`)。 */
    private data class EmbedThumbnail(val url: String)

    private data class EmbedField(val name: String, val value: String)

    /** 429 レスポンスボディ(必要なフィールドのみ)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RateLimitBody(
        @param:JsonProperty("retry_after") val retryAfter: Double?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(DiscordWebhookClient::class.java)

        /** Discord embed の author name 最大文字数。 */
        private const val MAX_AUTHOR_NAME_LENGTH = 256

        /** Discord embed の title 最大文字数。 */
        private const val MAX_TITLE_LENGTH = 256

        /** Discord embed field の value 最大文字数。 */
        private const val MAX_FIELD_VALUE_LENGTH = 1024

        /** 要約 field の見出し(常にこの固定文言。モデル生成の見出しは使わない)。 */
        private const val SUMMARY_FIELD_NAME = "要約"

        /** 最後に単独で送る導線のタイトル(サイトへのリンク)。 */
        private const val CTA_TITLE = "🔗 求人で注目の技術と記事をサイトで見る"

        /** Retry-After ヘッダもボディも取れなかった 429 の既定待機(即リトライで叩き続けないため)。 */
        private const val DEFAULT_RETRY_MS = 1000L

        private const val ELLIPSIS = "…"
    }
}
