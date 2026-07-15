package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 記事ページの HTML から OGP のサムネイル画像 URL を取り出す(infrastructure)。
 *
 * Discord は Webhook で渡した embed をそのまま描画し、リンク先の OGP を自前で読みには行かない。
 * よってサムネイルを出すには、こちらで記事ページを取得して `og:image` を解決してやる必要がある。
 *
 * 取得先は任意の外部サイトなので、応答しないページでダイジェスト全体が止まらないよう
 * タイムアウトと本文サイズ上限を設ける([FeedParser] 実装の RomeFeedParser と同じ方針)。
 * 取得・解析の失敗は握りつぶして null を返し、サムネイルなしで投稿を続けさせる。
 *
 * [timeoutMs] は **1 リクエストあたり**の上限であり、1 記事あたりではない。jsoup はリダイレクトの
 * ホップごとにタイムアウトを取り直すため、1 記事の解決は最悪 [timeoutMs] × 20(jsoup のリダイレクト
 * 上限)まで伸び得る。
 */
@Component
@ConditionalOnNotifyEnabled
class OgpThumbnailResolver(
    @Value("\${rss-watch.notify.ogp.timeout-ms:5000}") private val timeoutMs: Int = 5_000,
    @Value("\${rss-watch.notify.ogp.max-body-bytes:1048576}") private val maxBodyBytes: Int = 1_048_576,
    private val fetchDocument: (String) -> Document = { url ->
        Jsoup
            .connect(url)
            .userAgent(USER_AGENT)
            .timeout(timeoutMs)
            .maxBodySize(maxBodyBytes)
            .followRedirects(true)
            .get()
    },
) : ThumbnailResolver {

    /**
     * 取得だけでなく**解析**も含めてメソッド本体ごと runCatching で包む([ThumbnailResolver] の契約が
     * 「取得や解析に失敗した場合は null」であるため)。ここで例外を漏らすと呼び出し元の
     * BuildDigestUseCase.run() ごと落ち、サムネイル 1 枚のためにダイジェスト全体を失う。
     */
    override fun resolve(articleUrl: String): String? =
        runCatching {
            val document = fetchDocument(articleUrl)
            IMAGE_META_SELECTORS.firstNotNullOfOrNull { document.imageUrlOrNull(it) }
        }.onFailure { log.debug("failed to resolve thumbnail for article page: {}", articleUrl, it) }
            .getOrNull()

    /**
     * [selector] に一致する meta タグの content を絶対 URL として返す。
     * 一致が無い・どの候補も使えない場合は null。
     *
     * 同じ selector に複数一致し得る(`og:image` が複数あるページ)ため、1 つ目が空や非 http でも
     * 諦めずに順に候補を見る。
     *
     * `absUrl` は content が相対パスなら記事 URL 起点で絶対化し、解決できなければ空文字を返す。
     * ただし content 自体が空だと「記事 URL からの相対解決」が記事 URL 自身に化けるため、
     * 記事ページの URL をサムネイルとして送ってしまわないよう absUrl の前に弾く。
     */
    private fun Document.imageUrlOrNull(selector: String): String? =
        select(selector).firstNotNullOfOrNull { meta ->
            if (meta.attr("content").isBlank()) {
                null
            } else {
                meta.absUrl("content").takeIf { it.isHttpUrl() }
            }
        }

    /**
     * http(s) の URL だけを通す。Discord は他スキーム(`data:` 等)の画像 URL を 400 で弾き、
     * 400 になるとその記事だけ投稿がスキップされてしまうため、送る前に落とす。
     *
     * スキーム接頭辞で判定する。`URI()` による判定はスペースやパイプを含む URL
     * (`https://cdn.example.com/my image.png` 等)で URISyntaxException を投げ、Discord なら
     * 受け付ける正当な CDN の画像まで捨ててしまうため使わない。
     */
    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    companion object {
        private val log = LoggerFactory.getLogger(OgpThumbnailResolver::class.java)

        /** 先に一致したものを採用する(OGP 標準 → Twitter Card の順)。 */
        private val IMAGE_META_SELECTORS =
            listOf(
                """meta[property="og:image"]""",
                """meta[property="og:image:url"]""",
                """meta[name="twitter:image"]""",
            )

        /** Java デフォルト UA は Cloudflare 系サイトで 403 になりやすいため明示する。 */
        private const val USER_AGENT = "rss-watch/0.1 (+https://github.com/rockyh0825/tech-and-job-rss-reader)"
    }
}
