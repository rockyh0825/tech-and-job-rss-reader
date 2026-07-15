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
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
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
 * ## エラーの扱い
 *
 * 失敗を「その記事固有か / Discord へ到達できないか」で切り分ける(詳細は [retryWaitMsOrNull] と
 * [terminalResult])。リトライ回数は **1 通ごと**に数え直すため、ある記事での 429 が後続の記事の
 * リトライ余力を削らない。
 *
 * | 種別 | 扱い |
 * | --- | --- |
 * | 429 | `Retry-After` を尊重して [maxRetries] 回までリトライ。使い切ったら打ち切り。指示が [MAX_RETRY_WAIT_MS] 超なら待たずに打ち切り |
 * | 5xx / 接続失敗 | [TRANSIENT_RETRY_MS] 待って [maxRetries] 回までリトライ。使い切ったら打ち切り |
 * | 400 | その記事だけスキップして次へ進む(ペイロード固有の問題) |
 * | その他の 4xx | 打ち切り(Webhook URL 自体が無効・削除済み) |
 *
 * サイト導線を送る条件は「打ち切らずに最後まで記事を投稿し終えた、かつ 1 件以上投稿できた」。
 * 400 でスキップした記事があっても導線は送る。スキップされた記事は翌日また同じ 400 を踏む見込みだが、
 * それを理由に導線まで止めるのは「リンクは最後に来る」という元の要件の意図に反するため。
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
            when (val result = sendWithRetry(toEmbed(digest, article))) {
                is SendResult.Sent -> postedGuids += article.guid
                is SendResult.Skipped ->
                    // この記事のペイロード固有の問題。他の記事は無関係なので投稿を続ける。
                    // 通知済みにはしない(翌日も同じ 400 を踏む見込みだが、1 通ぶんのリトライは安いので
                    // 毎日試す方を採る。恒久的に隠すと直っても二度と出ない。窓 = window-days から
                    // 落ちれば自然に消える)。
                    log.warn("記事の投稿を拒否されたためこの記事のみスキップします: {}", article.url, result.error)
                is SendResult.Aborted -> {
                    // 叩き続けても同じ結果になるので以降は送らない。未投稿ぶんは次回の巡回で再度候補に上がる。
                    log.warn("記事の投稿に失敗したため以降の投稿を中断します: {}", article.url, result.error)
                    return PostOutcome(postedGuids, result.error)
                }
            }
        }

        // 記事が 1 件も無ければ導線だけ送っても意味がない。
        if (postedGuids.isEmpty()) return PostOutcome(emptyList())

        // 打ち切らずに最後まで走り切り、かつ 1 件以上投稿できたので導線を送る。
        // スキップした記事があっても送る: スキップぶんは翌日また試すが、それが通るまで導線を
        // 止め続けるのは「リンクは最後に来る」という元の要件の意図に反する。
        when (val result = sendWithRetry(ctaEmbed())) {
            is SendResult.Sent -> Unit
            // 記事自体は届いているため通知済みとして扱う(ここで失敗を返すと翌日これらを重複投稿してしまう)。
            is SendResult.Failed -> log.warn("記事は投稿できましたが、サイト導線の投稿に失敗しました", result.error)
        }
        return PostOutcome(postedGuids)
    }

    /**
     * embed 1 つを 1 通として送り、結果を [SendResult] に分類して返す。
     *
     * 429 は `Retry-After` に従って、5xx と接続失敗は [TRANSIENT_RETRY_MS] 待って [maxRetries] 回まで
     * 再試行する。再試行回数はこの呼び出し内で数えるため、1 通ごとに満額の余力が与えられる。
     */
    private fun sendWithRetry(embed: Embed): SendResult {
        val payload = WebhookPayload(embeds = listOf(embed))
        var attempt = 0
        while (true) {
            val error = runCatching { send(payload) }.exceptionOrNull() ?: return SendResult.Sent
            val retryWaitMs = retryWaitMsOrNull(error)
            // リトライしても無駄なエラー(4xx)は、その場で「スキップ」か「打ち切り」かを決める。
            if (retryWaitMs == null) return terminalResult(error)
            // 指示された待機が長すぎる。眠らずに打ち切って翌日へ回す(理由は [MAX_RETRY_WAIT_MS])。
            if (retryWaitMs > MAX_RETRY_WAIT_MS) return SendResult.Aborted(error)
            // リトライ余力を使い切った 429 / 5xx / 接続失敗。以降の通も同じ状況に置かれるので打ち切る。
            if (attempt >= maxRetries) return SendResult.Aborted(error)
            sleeper(retryWaitMs)
            attempt++
        }
    }

    /**
     * 再試行する価値のあるエラーなら待機ミリ秒、無駄なエラーなら null を返す。
     *
     * 429 はレート制限、5xx は Discord 側の一時障害、[ResourceAccessException] は接続失敗
     * (RestClient が [java.io.IOException] を包んだもの)で、いずれも時間を置けば通り得る。
     *
     * [java.io.IOException] 自体は分岐に持たない。RestClient は送信・受信で起きた IOException を必ず
     * [ResourceAccessException](RuntimeException 系)に包んで投げるため、ここまで素の IOException は届かない。
     */
    private fun retryWaitMsOrNull(error: Throwable): Long? =
        when (error) {
            is HttpClientErrorException.TooManyRequests -> retryAfterMs(error)
            is HttpServerErrorException, is ResourceAccessException -> TRANSIENT_RETRY_MS
            else -> null
        }

    /**
     * リトライしても無駄なエラーを「その記事だけスキップ」と「以降ごと打ち切り」に振り分ける。
     *
     * 400 はそのペイロード固有の問題(記事 A が 400 でも記事 B の妥当性とは無関係)なのでスキップに留める。
     * ここで打ち切ると、失敗した記事は通知済みにならないため翌朝も同じダイジェストが組み上がり、同じ 400 で
     * 全滅する = 窓から落ちるまで記事も導線も 1 通も届かない。
     *
     * それ以外の 4xx(401/403/404 等)は Webhook URL 自体が無効・削除済みなので、以降も必ず失敗する。
     */
    private fun terminalResult(error: Throwable): SendResult =
        when (error) {
            is HttpClientErrorException.BadRequest -> SendResult.Skipped(error)
            else -> SendResult.Aborted(error)
        }

    /** 1 通の投稿結果。 */
    private sealed interface SendResult {
        /** 投稿できた。 */
        data object Sent : SendResult

        /** 失敗した(スキップと打ち切りに共通のエラー保持)。 */
        sealed interface Failed : SendResult {
            val error: Throwable
        }

        /** この通だけ捨てて次へ進む(400)。 */
        data class Skipped(override val error: Throwable) : Failed

        /** 以降の投稿ごと打ち切る。 */
        data class Aborted(override val error: Throwable) : Failed
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
     * 429 の待機ミリ秒を返す。
     * 待機時間は `Retry-After` ヘッダ(秒)→ JSON ボディの `retry_after`(秒)→ 既定値の順に採用する。
     *
     * RFC 7231 上 `Retry-After` は HTTP-date 形式も取り得るが、Discord は常に数値秒を返す。
     * よって数値秒(`toDoubleOrNull`)のみ対応し、date 形式ならボディ→既定値へフォールバックする。
     *
     * サーバの指示は尊重するが [MIN_RETRY_MS] を下限にする。`Retry-After: 0`(や負値)をそのまま信じると
     * 待たずに投げ直してレート制限を叩き続けてしまうため。Discord は 0.5 秒等の小数秒も返すので、
     * 下限は「即時リトライを防ぐ」だけの短い値に留め、正当な小数秒の指示は潰さない。
     */
    private fun retryAfterMs(error: HttpClientErrorException.TooManyRequests): Long {
        val seconds =
            error.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)?.toDoubleOrNull()
                ?: bodyRetryAfterSeconds(error)
        return seconds?.let { (it * 1000).toLong().coerceAtLeast(MIN_RETRY_MS) } ?: DEFAULT_RETRY_MS
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

    /**
     * 技術グループの見出し文言(例: 「🧩 Kotlin ・ 求人 5 件で言及」)。
     * 興味技術は ⭐、求人に出ていない技術(mentionCount=0)は「求人 0 件で言及」の代わりに「新着記事」。
     */
    private fun authorLabel(digest: TechDigest): String {
        val icon = if (digest.interested) "⭐" else "🧩"
        val suffix = if (digest.mentionCount > 0) "求人 ${digest.mentionCount} 件で言及" else "新着記事"
        return "$icon ${digest.keyword} ・ $suffix"
    }

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

        /** `Retry-After: 0` 等をそのまま信じて即時リトライにならないための下限。 */
        private const val MIN_RETRY_MS = 200L

        /**
         * サーバの指示に従って待つ上限。これを超える指示は待たずに打ち切る。
         *
         * Discord の通常のレート制限(per-route)は秒オーダーで解ける。それを大きく超える指示が来るのは
         * グローバル制限や Cloudflare 1015 の BAN で、いずれもこの実行中には解けない規模
         * (`Retry-After: 3600` なら [maxRetries] 次第で最悪 2 時間)。ダイジェストは日次なので、
         * スケジューラのスレッドを何時間も占有するより翌日の巡回へ回す方が安い。
         *
         * 上限でクランプして「60 秒だけ待って再送」はしない。Discord の指示より早く叩き直すことになり、
         * レート制限をさらに踏む。待てないなら諦める、が筋。
         */
        private const val MAX_RETRY_WAIT_MS = 60_000L

        /** 5xx・接続失敗の待機(サーバからの指示が無いので固定値)。 */
        private const val TRANSIENT_RETRY_MS = 1000L

        private const val ELLIPSIS = "…"
    }
}
