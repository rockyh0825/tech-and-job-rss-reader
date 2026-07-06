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
    }
}
