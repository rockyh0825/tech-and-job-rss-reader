package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.JWTProcessor
import java.net.URI
import org.slf4j.LoggerFactory
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
        val host = teamDomain.removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
        // team ドメインは素のホスト(例 myteam.cloudflareaccess.com)。パス/ポート/空白が混じると
        // issuer・JWKS URL が黙って壊れる(誤設定は iss 不一致で全 401 に倒れるが、原因が分かりにくい)ため弾く。
        require(host.isNotEmpty() && '/' !in host && ':' !in host && ' ' !in host) {
            "rss-watch.access.team-domain must be a bare host like myteam.cloudflareaccess.com, but was: $teamDomain"
        }
        val issuer = "https://$host"
        val certsUrl = URI.create("https://$host/cdn-cgi/access/certs").toURL()
        // 運用者が導出結果を確認できるよう起動時に出す(トークンではないので秘匿不要)。
        logger.info("Cloudflare Access JWT verification enabled (issuer={}, jwks={})", issuer, certsUrl)
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

    companion object {
        private val logger = LoggerFactory.getLogger(AccessJwtConfig::class.java)
    }
}
