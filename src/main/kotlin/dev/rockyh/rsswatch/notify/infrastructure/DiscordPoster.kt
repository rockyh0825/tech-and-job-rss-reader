package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.rockyh.rsswatch.notify.domain.DiscordMessageRef
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

/**
 * Discord Webhook へのダイジェスト投稿の transport(チャンネル非依存の共有部品)。
 * Spring Bean にはせず、各 Webhook クライアント(チャンネルごとの `@Component`)が
 * 自分の Webhook URL で組み立てて保持する。
 *
 * **記事 1 件 = 1 通**(embed 1 つ)で [postArticles] に渡された順に投稿し、打ち切らずに最後まで
 * 投稿し終えて 1 件以上投稿できたときだけ、最後に導線 embed を単独の 1 通として送る。
 *
 * 1 通にまとめず記事ごとに分けているため、Discord 側の「10 embed/通・合計 6000 文字/通」の上限に
 * 記事数が縛られない(単一 embed の title 256・field value 1024 の上限は [clampTo] で守る)。
 * 代わりに投稿は途中で失敗し得るので、どこまで投稿できたかを [PostOutcome] で呼び出し側へ返す。
 * 候補 0 件の日の導線のみ投稿([postCtaOnly])だけは例外的に、複数の導線 embed を 1 通にまとめる。
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
 * 導線を送る条件は「打ち切らずに最後まで記事を投稿し終えた、かつ 1 件以上投稿できた」。
 * 400 でスキップした記事があっても導線は送る。スキップされた記事は翌日また同じ 400 を踏む見込みだが、
 * それを理由に導線まで止めるのは「リンクは最後に来る」という元の要件の意図に反するため。
 */
class DiscordPoster(
    restClientBuilder: RestClient.Builder,
    private val webhookUrl: String,
    private val maxRetries: Int,
    private val sleeper: (Long) -> Unit,
) {

    private val restClient: RestClient = restClientBuilder.build()

    /**
     * 実際の POST 先。`?wait=true` を付けると Discord は作成されたメッセージ(id, channel_id)を
     * レスポンスで返す(付けないと 204 No Content)。リアクション・返信の回収で記事と突き合わせるため、
     * メッセージ ID を [PostOutcome.postedMessages] として呼び出し側へ返す。
     */
    private val postUrl = "$webhookUrl?wait=true"

    /** 投稿 1 通ぶんの単位。[guid] は投稿できた記事の記録([PostOutcome.postedGuids])に使う。 */
    data class ArticlePost(val guid: String, val embed: Embed)

    fun postArticles(posts: List<ArticlePost>, ctaEmbed: Embed): PostOutcome {
        val postedGuids = mutableListOf<String>()
        val postedMessages = mutableListOf<DiscordMessageRef>()
        for (post in posts) {
            when (val result = sendWithRetry(listOf(post.embed))) {
                is SendResult.Sent -> {
                    postedGuids += post.guid
                    result.message?.let {
                        postedMessages += DiscordMessageRef(guid = post.guid, channelId = it.channelId, messageId = it.id)
                    }
                }
                is SendResult.Skipped ->
                    // この記事のペイロード固有の問題。他の記事は無関係なので投稿を続ける。
                    // 通知済みにはしない(翌日も同じ 400 を踏む見込みだが、1 通ぶんのリトライは安いので
                    // 毎日試す方を採る。恒久的に隠すと直っても二度と出ない。窓 = window-days から
                    // 落ちれば自然に消える)。
                    log.warn("記事の投稿を拒否されたためこの記事のみスキップします: {}", post.embed.url, result.error)
                is SendResult.Aborted -> {
                    // 叩き続けても同じ結果になるので以降は送らない。未投稿ぶんは次回の巡回で再度候補に上がる。
                    log.warn("記事の投稿に失敗したため以降の投稿を中断します: {}", post.embed.url, result.error)
                    return PostOutcome(postedGuids, result.error, postedMessages)
                }
            }
        }

        // 記事が 1 件も無ければ導線だけ送っても意味がない。
        if (postedGuids.isEmpty()) return PostOutcome(emptyList())

        // 打ち切らずに最後まで走り切り、かつ 1 件以上投稿できたので導線を送る。
        // スキップした記事があっても送る: スキップぶんは翌日また試すが、それが通るまで導線を
        // 止め続けるのは「リンクは最後に来る」という元の要件の意図に反する。
        when (val result = sendWithRetry(listOf(ctaEmbed))) {
            is SendResult.Sent -> Unit
            // 記事自体は届いているため通知済みとして扱う(ここで失敗を返すと翌日これらを重複投稿してしまう)。
            is SendResult.Failed -> log.warn("記事は投稿できましたが、サイト導線の投稿に失敗しました", result.error)
        }
        return PostOutcome(postedGuids, failure = null, postedMessages = postedMessages)
    }

    /**
     * 導線 embed だけを 1 通で送る(候補 0 件の日用)。記事は無いので [PostOutcome.postedGuids] は常に空。
     * 1 通しか送らないためスキップと打ち切りの区別は不要で、失敗はそのまま [PostOutcome.failure] で返す。
     * リトライ・レート制限の扱いは [sendWithRetry] に準じる。
     */
    fun postCtaOnly(embeds: List<Embed>): PostOutcome =
        when (val result = sendWithRetry(embeds)) {
            is SendResult.Sent -> PostOutcome(emptyList())
            is SendResult.Failed -> PostOutcome(emptyList(), result.error)
        }

    /**
     * embed 一式を 1 通として送り、結果を [SendResult] に分類して返す。
     *
     * 429 は `Retry-After` に従って、5xx と接続失敗は [TRANSIENT_RETRY_MS] 待って [maxRetries] 回まで
     * 再試行する。再試行回数はこの呼び出し内で数えるため、1 通ごとに満額の余力が与えられる。
     */
    private fun sendWithRetry(embeds: List<Embed>): SendResult {
        val payload = WebhookPayload(embeds = embeds)
        var attempt = 0
        while (true) {
            val sent = runCatching { send(payload) }
            val error = sent.exceptionOrNull() ?: return SendResult.Sent(sent.getOrNull())
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
        /** 投稿できた。[message] はレスポンスから解析した作成済みメッセージ(解析できなければ null)。 */
        data class Sent(val message: CreatedMessage?) : SendResult

        /** 失敗した(スキップと打ち切りに共通のエラー保持)。 */
        sealed interface Failed : SendResult {
            val error: Throwable
        }

        /** この通だけ捨てて次へ進む(400)。 */
        data class Skipped(override val error: Throwable) : Failed

        /** 以降の投稿ごと打ち切る。 */
        data class Aborted(override val error: Throwable) : Failed
    }

    /**
     * 1 通を POST し、レスポンスから作成されたメッセージを解析して返す。
     *
     * ボディの解析はベストエフォート: HTTP レベルで成功していれば投稿自体は完了しているため、
     * ボディが想定外でも例外にせず null を返す(メッセージ対応が取れないだけで投稿の成否は変えない)。
     */
    private fun send(payload: WebhookPayload): CreatedMessage? {
        val body =
            restClient
                .post()
                .uri(postUrl)
                .header("content-type", "application/json")
                .body(payload)
                .retrieve()
                .body(String::class.java)
        return parseCreatedMessage(body)
    }

    /** `?wait=true` のレスポンスボディからメッセージ(id, channel_id)を読み取る(解析失敗は null)。 */
    private fun parseCreatedMessage(body: String?): CreatedMessage? {
        if (body.isNullOrBlank()) return null
        val parsed = runCatching { mapper.readValue<CreatedMessageBody>(body) }.getOrNull() ?: return null
        val id = parsed.id ?: return null
        val channelId = parsed.channelId ?: return null
        return CreatedMessage(id = id, channelId = channelId)
    }

    /** `?wait=true` のレスポンスから解析した、作成済みメッセージの所在。 */
    data class CreatedMessage(val id: String, val channelId: String)

    /** `?wait=true` のレスポンスボディ(必要なフィールドのみ)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CreatedMessageBody(
        val id: String?,
        @param:JsonProperty("channel_id") val channelId: String?,
    )

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class WebhookPayload(val embeds: List<Embed>)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Embed(
        val author: EmbedAuthor?,
        val title: String,
        val url: String,
        val description: String?,
        val thumbnail: EmbedThumbnail?,
        val fields: List<EmbedField>?,
    )

    data class EmbedAuthor(val name: String)

    /** embed 右上に小さく出る画像(Discord の `thumbnail`)。 */
    data class EmbedThumbnail(val url: String)

    data class EmbedField(val name: String, val value: String)

    /** 429 レスポンスボディ(必要なフィールドのみ)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RateLimitBody(
        @param:JsonProperty("retry_after") val retryAfter: Double?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(DiscordPoster::class.java)

        /** レスポンスボディの解析用(送信ペイロードは RestClient の変換に任せるため送信側では使わない)。 */
        private val mapper = jacksonObjectMapper()

        /** Discord embed の author name 最大文字数。 */
        const val MAX_AUTHOR_NAME_LENGTH = 256

        /** Discord embed の title 最大文字数。 */
        const val MAX_TITLE_LENGTH = 256

        /** Discord embed field の value 最大文字数。 */
        const val MAX_FIELD_VALUE_LENGTH = 1024

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
    }
}

/**
 * Discord の文字数上限を超える場合は末尾を省略記号で切り詰める。上限内はそのまま返す。
 * サロゲートペア(絵文字等)の途中で切って壊れた文字を残さないよう、境界直前が
 * high surrogate なら 1 code unit 手前で切る。
 *
 * 上限 [max] は UTF-16 code unit(`.length`)で判定する。Discord の上限は本来 Unicode コードポイント
 * 基準だが、code unit 計数は常にコードポイント数以上になるため保守的(安全側)であり、400 を招かない
 * (絵文字主体のテキストでは必要以上に切る可能性はある)。これは意図的な選択。
 */
internal fun String.clampTo(max: Int): String {
    if (length <= max) return this
    var end = max - "…".length
    if (end > 0 && this[end - 1].isHighSurrogate()) end--
    return substring(0, end) + "…"
}

/** 既定のサイト一覧 URL(`rss-watch.notify.site-url` 未設定時)。通常・CNCF 両クライアントで共有する。 */
internal const val DEFAULT_SITE_URL = "https://rss-watch.rocky-ha.com/"

/**
 * サイト一覧への導線 embed。通常ダイジェストの末尾と CNCF ダイジェストの候補 0 件時で共有する
 * (「通常と同じ導線」という要件を文言ごと構造的に保つ)。
 */
internal fun siteCtaEmbed(siteUrl: String): DiscordPoster.Embed =
    DiscordPoster.Embed(
        author = null,
        title = "🔗 注目の技術と記事をサイトで見る",
        url = siteUrl,
        description = null,
        thumbnail = null,
        fields = null,
    )
