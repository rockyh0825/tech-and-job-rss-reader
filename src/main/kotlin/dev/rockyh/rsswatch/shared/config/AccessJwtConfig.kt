package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.JWTProcessor
import java.net.URI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Cloudflare Access JWT 検証フィルタの配線(任意ハードニング / Phase 2)。
 *
 * `rss-watch.access.aud` が設定されているときだけ Bean を登録する。未設定ならフィルタは一切登録されず、
 * アプリは従来どおり無認証で起動する(=既定はアプリ無改造の挙動を維持)。有効化すると、
 * Cloudflare Access を経由しない全リクエスト(LAN からの直アクセス含む)が 401 で拒否される。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["rss-watch.access.aud"])
@EnableConfigurationProperties(AccessJwtProperties::class)
class AccessJwtConfig {

    @Bean
    fun accessJwtProcessor(properties: AccessJwtProperties): JWTProcessor<SecurityContext> {
        val teamDomain =
            requireNotNull(properties.teamDomain?.takeIf { it.isNotBlank() }) {
                "rss-watch.access.team-domain must be set when rss-watch.access.aud is set"
            }
        val aud =
            requireNotNull(properties.aud?.takeIf { it.isNotBlank() }) {
                "rss-watch.access.aud must not be blank"
            }
        val host = teamDomain.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val issuer = "https://$host"
        val certsUrl = URI.create("https://$host/cdn-cgi/access/certs").toURL()
        return AccessJwtProcessors.remote(certsUrl, issuer, aud)
    }

    @Bean
    fun accessJwtFilterRegistration(
        accessJwtProcessor: JWTProcessor<SecurityContext>,
    ): FilterRegistrationBean<AccessJwtFilter> {
        val registration = FilterRegistrationBean(AccessJwtFilter(accessJwtProcessor))
        // 認証は他フィルタより前で行う(未認証リクエストを早期に弾く)。
        registration.order = Ordered.HIGHEST_PRECEDENCE
        registration.addUrlPatterns("/*")
        return registration
    }
}
