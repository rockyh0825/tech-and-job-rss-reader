package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import java.net.URI
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

    override fun resolve(articleUrl: String): String? {
        val document =
            runCatching { fetchDocument(articleUrl) }
                .onFailure { log.debug("failed to fetch article page for thumbnail: {}", articleUrl, it) }
                .getOrNull() ?: return null
        return IMAGE_META_SELECTORS.firstNotNullOfOrNull { document.imageUrlOrNull(it) }
    }

    /**
     * [selector] に一致する meta タグの content を絶対 URL として返す。
     * 見つからない・中身が空・相対解決できない・http(s) でない場合は null。
     *
     * `absUrl` は content が相対パスなら記事 URL 起点で絶対化し、解決できなければ空文字を返す。
     * ただし content 自体が空だと「記事 URL からの相対解決」が記事 URL 自身に化けるため、
     * 記事ページの URL をサムネイルとして送ってしまわないよう absUrl の前に弾く。
     */
    private fun Document.imageUrlOrNull(selector: String): String? {
        val meta = selectFirst(selector) ?: return null
        if (meta.attr("content").isBlank()) return null
        return meta.absUrl("content").takeIf { it.isHttpUrl() }
    }

    /**
     * http(s) の URL だけを通す。Discord は他スキーム(`data:` 等)の画像 URL を 400 で弾き、
     * 400 になるとその記事の投稿が失敗して以降の投稿ごと打ち切られてしまうため、送る前に落とす。
     */
    private fun String.isHttpUrl(): Boolean {
        val scheme = runCatching { URI(this).scheme }.getOrNull()?.lowercase()
        return scheme == "http" || scheme == "https"
    }

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
