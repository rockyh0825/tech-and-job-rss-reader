package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTProcessor
import java.net.URL

/**
 * Cloudflare Access の JWT を検証する [JWTProcessor] を組み立てるファクトリ。
 *
 * 検証ルール(署名 RS256・aud・iss・exp)を本番とテストで同一にするため一箇所に集約する。
 * 本番は Cloudflare の JWKS を [remote] でネットワーク取得(RemoteJWKSet がキャッシュと鍵ローテーションを担う)、
 * テストはローカル生成鍵の公開鍵を JWKS スタブとして [create] に渡す。
 */
object AccessJwtProcessors {

    /**
     * 任意の [jwkSource] で検証器を組み立てる。
     * @param issuer 期待する iss(例 `https://<team>.cloudflareaccess.com`)
     * @param audience 期待する aud(Access アプリの AUD タグ)
     */
    fun create(
        jwkSource: JWKSource<SecurityContext>,
        issuer: String,
        audience: String,
    ): JWTProcessor<SecurityContext> {
        val processor = DefaultJWTProcessor<SecurityContext>()
        // Cloudflare Access は RS256 で署名する。他アルゴリズムは受け付けない(alg 混同攻撃の回避)。
        processor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        processor.setJWTClaimsSetVerifier(
            DefaultJWTClaimsVerifier<SecurityContext>(
                audience,
                JWTClaimsSet.Builder().issuer(issuer).build(),
                setOf("exp"),
            ),
        )
        return processor
    }

    /** Cloudflare の JWKS エンドポイントからネットワーク取得する本番用検証器。 */
    fun remote(certsUrl: URL, issuer: String, audience: String): JWTProcessor<SecurityContext> =
        create(RemoteJWKSet(certsUrl), issuer, audience)
}
