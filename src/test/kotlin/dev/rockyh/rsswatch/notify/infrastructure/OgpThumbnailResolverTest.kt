package dev.rockyh.rsswatch.notify.infrastructure

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.junit.jupiter.api.Test

class OgpThumbnailResolverTest {

    private val articleUrl = "https://example.com/posts/kotlin-coroutines"

    /** ページ HTML を [html] に差し替えた resolver(取得部分は seam で置き換え、パース・検証を検証する)。 */
    private fun resolver(html: String): OgpThumbnailResolver =
        OgpThumbnailResolver(fetchDocument = { url -> Jsoup.parse(html, url) })

    /** 記事ページの取得自体が失敗する resolver。 */
    private fun failingResolver(error: Throwable): OgpThumbnailResolver =
        OgpThumbnailResolver(fetchDocument = { throw error })

    private fun page(vararg metaTags: String): String =
        """
        <html><head><title>記事</title>${metaTags.joinToString("")}</head>
        <body><p>本文</p></body></html>
        """.trimIndent()

    @Test
    fun returns_the_og_image_url_when_the_page_has_one() {
        val html = page("""<meta property="og:image" content="https://cdn.example.com/thumb.png">""")

        assertEquals("https://cdn.example.com/thumb.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun resolves_a_relative_og_image_url_against_the_article_url() {
        // 相対パスのまま Discord へ渡すと画像が出ない(かつ 400 の元)ので絶対 URL に直す
        val html = page("""<meta property="og:image" content="/images/thumb.png">""")

        assertEquals("https://example.com/images/thumb.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun falls_back_to_og_image_url_variant_when_plain_og_image_is_absent() {
        val html = page("""<meta property="og:image:url" content="https://cdn.example.com/variant.png">""")

        assertEquals("https://cdn.example.com/variant.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun falls_back_to_twitter_image_when_no_og_image_tag_exists() {
        val html = page("""<meta name="twitter:image" content="https://cdn.example.com/tw.png">""")

        assertEquals("https://cdn.example.com/tw.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun prefers_og_image_over_twitter_image_when_both_exist() {
        val html =
            page(
                """<meta name="twitter:image" content="https://cdn.example.com/tw.png">""",
                """<meta property="og:image" content="https://cdn.example.com/og.png">""",
            )

        assertEquals("https://cdn.example.com/og.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_page_has_no_image_meta_tag() {
        assertNull(resolver(page()).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_og_image_content_is_blank() {
        val html = page("""<meta property="og:image" content="   ">""")

        assertNull(resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_article_page_cannot_be_fetched() {
        // 記事ページが落ちていてもダイジェスト投稿自体は続けたいので、例外は外に出さず null にする
        assertNull(failingResolver(IOException("404 Not Found")).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_image_url_is_a_data_uri() {
        // Discord は http(s) 以外の画像 URL を 400 で弾く。400 になるとその記事の投稿ごと
        // スキップされてしまう(サムネイルのせいで記事が落ちる)ため、送る前に落とす
        val html = page("""<meta property="og:image" content="data:image/png;base64,iVBORw0KGgo=">""")

        assertNull(resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_image_url_scheme_is_not_http() {
        val html = page("""<meta property="og:image" content="ftp://files.example.com/thumb.png">""")

        assertNull(resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_parsing_the_page_throws() {
        // 契約は「取得や解析に失敗した場合は null」。解析側が投げてもダイジェスト投稿全体を巻き込まない
        assertNull(parseFailingResolver(IllegalStateException("boom")).resolve(articleUrl))
    }

    @Test
    fun keeps_an_image_url_that_contains_a_space() {
        // URI() はスペースで例外を投げる。正当な CDN URL のサムネイルを捨てないこと
        val html = page("""<meta property="og:image" content="https://cdn.example.com/my image.png">""")

        assertEquals("https://cdn.example.com/my image.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun keeps_an_image_url_that_contains_a_pipe() {
        // 同上。Firebase Storage 等が生成する URL にはパイプが含まれ得る
        val html = page("""<meta property="og:image" content="https://example.com/o/b|c.png">""")

        assertEquals("https://example.com/o/b|c.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun falls_back_to_the_second_candidate_of_the_same_selector_when_the_first_is_blank() {
        // 同じ og:image がページ内に複数あり 1 つ目が空 → 2 つ目を見ずに諦めない
        val html =
            page(
                """<meta property="og:image" content="   ">""",
                """<meta property="og:image" content="https://cdn.example.com/second.png">""",
            )

        assertEquals("https://cdn.example.com/second.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun falls_back_to_the_second_candidate_of_the_same_selector_when_the_first_is_not_http() {
        val html =
            page(
                """<meta property="og:image" content="data:image/png;base64,iVBORw0KGgo=">""",
                """<meta property="og:image" content="https://cdn.example.com/second.png">""",
            )

        assertEquals("https://cdn.example.com/second.png", resolver(html).resolve(articleUrl))
    }

    @Test
    fun fetches_the_page_over_http_with_the_default_fetcher() {
        // 既定の fetcher(Jsoup.connect)は seam を差し替える他のテストでは通らないため、
        // ローカルの実 HTTP サーバ相手に「実際に取得して相対 URL を絶対化できる」ところまで確かめる
        val html = page("""<meta property="og:image" content="/thumb.png">""")
        withLocalHttpServer(html) { baseUrl ->
            assertEquals("$baseUrl/thumb.png", OgpThumbnailResolver().resolve("$baseUrl/article"))
        }
    }

    @Test
    fun returns_null_when_the_page_is_not_html_at_all() {
        // 記事 URL が PDF 等を返すと Jsoup は UnsupportedMimeTypeException を投げる。
        // Jsoup.parse では再現できない経路なので、実 HTTP サーバに PDF の Content-Type を返させる
        withLocalHttpServer("%PDF-1.4 binary junk", contentType = "application/pdf") { baseUrl ->
            assertNull(OgpThumbnailResolver().resolve("$baseUrl/article"))
        }
    }

    @Test
    fun returns_null_when_the_default_fetcher_hits_the_configured_timeout() {
        // timeout-ms が既定 fetcher に実際に効いていること(取り違えても他のテストは緑のままなので明示的に見る)。
        // 応答を遅延させるサーバに、遅延より短い timeout を設定して取得を諦めさせる
        val html = page("""<meta property="og:image" content="/thumb.png">""")
        withLocalHttpServer(html, responseDelayMs = 1_000) { baseUrl ->
            val resolver = OgpThumbnailResolver(timeoutMs = 100)

            assertNull(resolver.resolve("$baseUrl/article"))
        }
    }

    /**
     * 解析側が投げるページ。取得は成功するが解析で落ちるケースを再現する。
     * 実装が select / selectFirst のどちらで走査しても投げるよう両方を塞ぐ。
     */
    private fun parseFailingResolver(error: Throwable): OgpThumbnailResolver =
        OgpThumbnailResolver(
            fetchDocument = {
                object : Document(articleUrl) {
                    override fun select(cssQuery: String): Elements = throw error

                    override fun selectFirst(cssQuery: String): Element = throw error
                }
            },
        )

    /**
     * `/article` で [body] を返すローカル HTTP サーバを立て、`http://127.0.0.1:<port>` を [block] に渡す。
     * [responseDelayMs] を与えるとレスポンス送出前にその時間だけ待つ(タイムアウトの検証用)。
     */
    private fun withLocalHttpServer(
        body: String,
        contentType: String = "text/html; charset=utf-8",
        responseDelayMs: Long = 0,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/article") { exchange ->
            if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
