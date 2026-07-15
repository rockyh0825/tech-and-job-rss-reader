package dev.rockyh.rsswatch.notify.infrastructure

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jsoup.Jsoup
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
        // Discord は http(s) 以外の画像 URL を 400 で弾く。400 になるとその記事の投稿が失敗し、
        // 記事ごと 1 通の投稿はそこで打ち切られてしまうため、送る前に落とす
        val html = page("""<meta property="og:image" content="data:image/png;base64,iVBORw0KGgo=">""")

        assertNull(resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_image_url_scheme_is_not_http() {
        val html = page("""<meta property="og:image" content="ftp://files.example.com/thumb.png">""")

        assertNull(resolver(html).resolve(articleUrl))
    }

    @Test
    fun returns_null_when_the_page_is_not_html_at_all() {
        assertNull(resolver("%PDF-1.4 binary junk").resolve(articleUrl))
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

    /** `/article` で [html] を返すローカル HTTP サーバを立て、`http://127.0.0.1:<port>` を [block] に渡す。 */
    private fun withLocalHttpServer(html: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/article") { exchange ->
            val body = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
