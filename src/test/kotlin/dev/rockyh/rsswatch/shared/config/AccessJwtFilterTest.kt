package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

/**
 * Cloudflare Access の Cf-Access-Jwt-Assertion 検証フィルタのテスト。
 * JWKS はネットワークに出ず、テスト内で生成した RSA 鍵の公開鍵をスタブ(ImmutableJWKSet)として使う。
 * 本番と同じ [AccessJwtProcessors.create] で検証器を組み立てるため、署名・aud・iss・exp のルールを実際に検証する。
 */
class AccessJwtFilterTest {

    @RestController
    class PingController {
        @GetMapping("/ping")
        fun ping(): String = "pong"
    }

    private val issuer = "https://myteam.cloudflareaccess.com"
    private val audience = "aud-tag-123"

    // 検証側が信頼する鍵ペア(この公開鍵だけが JWKS スタブに載る)
    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()

    private val mockMvc: MockMvc = buildMockMvc(issuer = issuer, audience = audience, jwkKey = signingKey)

    private fun buildMockMvc(issuer: String, audience: String, jwkKey: RSAKey): MockMvc {
        val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(jwkKey.toPublicJWK()))
        val processor = AccessJwtProcessors.create(jwkSource, issuer, audience)
        return MockMvcBuilders
            .standaloneSetup(PingController())
            .addFilters<StandaloneMockMvcBuilder>(AccessJwtFilter(processor))
            .build()
    }

    /** 指定の鍵・クレームで署名済み JWT を生成する。 */
    private fun signedToken(
        signingKey: RSAKey = this.signingKey,
        issuer: String = this.issuer,
        audience: String = this.audience,
        expiresAt: Date = Date(System.currentTimeMillis() + 60_000),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("viewer@example.com")
            .expirationTime(expiresAt)
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
        jwt.sign(RSASSASigner(signingKey))
        return jwt.serialize()
    }

    @Test
    fun passes_through_when_jwt_is_valid() {
        mockMvc
            .perform(get("/ping").header(AccessJwtFilter.HEADER, signedToken()))
            .andExpect(status().isOk)
    }

    @Test
    fun returns_401_when_jwt_header_is_missing() {
        mockMvc
            .perform(get("/ping"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_jwt_header_is_blank() {
        mockMvc
            .perform(get("/ping").header(AccessJwtFilter.HEADER, "   "))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_token_is_malformed() {
        mockMvc
            .perform(get("/ping").header(AccessJwtFilter.HEADER, "not-a-jwt"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_signature_key_is_untrusted() {
        // JWKS に載っていない別の鍵で署名(kid は同じでも鍵材が違えば署名検証に失敗する)
        val attackerKey = RSAKeyGenerator(2048).keyID("k1").generate()
        mockMvc
            .perform(get("/ping").header(AccessJwtFilter.HEADER, signedToken(signingKey = attackerKey)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_jwt_is_expired() {
        mockMvc
            .perform(
                get("/ping").header(
                    AccessJwtFilter.HEADER,
                    signedToken(expiresAt = Date(System.currentTimeMillis() - 60_000)),
                ),
            )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_audience_does_not_match() {
        mockMvc
            .perform(get("/ping").header(AccessJwtFilter.HEADER, signedToken(audience = "someone-elses-aud")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun returns_401_when_issuer_does_not_match() {
        mockMvc
            .perform(
                get("/ping").header(
                    AccessJwtFilter.HEADER,
                    signedToken(issuer = "https://evil.cloudflareaccess.com"),
                ),
            )
            .andExpect(status().isUnauthorized)
    }
}
