package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.JWTProcessor
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * オリジンで Cloudflare Access の `Cf-Access-Jwt-Assertion` を検証するフィルタ(防御多層化)。
 *
 * Cloudflare Access はエッジで認証を通したリクエストにのみ、署名済み JWT をこのヘッダで付与する。
 * したがってヘッダ欠落や検証失敗は「Access を経由していない」= 未認証を意味し、401 で拒否する。
 * これにより、Cloudflare を経由しない経路(自宅 LAN からの :8080 直アクセス)も遮断できる。
 *
 * この feature 全体は設定(`rss-watch.access.aud` / `team-domain`)がある時だけ有効になる([AccessJwtConfig])。
 */
class AccessJwtFilter(
    private val jwtProcessor: JWTProcessor<SecurityContext>,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI in EXCLUDED_PATHS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader(HEADER)
        if (token.isNullOrBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        try {
            // 署名・aud・iss・exp をまとめて検証する。不正なら例外が飛ぶ。
            jwtProcessor.process(token, null)
        } catch (ex: Exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "Cf-Access-Jwt-Assertion"

        /**
         * フィルタ対象外のパス(exact match)。localhost の Prometheus コンテナは Access を
         * 経由できずヘッダを持たないため、メトリクスと死活の 2 パスだけは無認証で通す。
         * actuator 配下全体のワイルドカード除外にしないのは、将来 expose を広げた場合に無認証を波及させないため
         * (除外リストと expose リストを同じ「最小限」で揃える)。exact match のため
         * `/actuator/health/liveness` 等のサブパスは除外されない(導入時に明示的に追加する)。
         * また `requestURI` への完全一致のため、将来 `server.servlet.context-path` を設定すると
         * URI にプレフィックスが付いて除外が外れる(fail-closed で 401 になる)点に注意。
         */
        private val EXCLUDED_PATHS = setOf("/actuator/health", "/actuator/prometheus")
    }
}
