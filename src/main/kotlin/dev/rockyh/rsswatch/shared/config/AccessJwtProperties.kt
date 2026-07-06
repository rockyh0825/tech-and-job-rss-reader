package dev.rockyh.rsswatch.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Cloudflare Access JWT 検証の設定。
 *
 * どちらも application.yml にデフォルト値を置かないこと(未設定=未定義で feature を無効にするため。[AccessJwtConfig])。
 *
 * @param teamDomain Zero Trust の team ドメイン(例 `myteam.cloudflareaccess.com`。`https://` 付きも可)
 * @param aud Access アプリの AUD タグ
 */
@ConfigurationProperties(prefix = "rss-watch.access")
data class AccessJwtProperties(
    val teamDomain: String? = null,
    val aud: String? = null,
)
