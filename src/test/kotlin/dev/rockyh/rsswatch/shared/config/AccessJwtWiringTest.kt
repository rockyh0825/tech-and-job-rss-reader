package dev.rockyh.rsswatch.shared.config

import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.JWTProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean

/**
 * [AccessJwtConfig] のトグル配線を検証する(セキュリティ境界そのものなので、フィルタ振る舞いとは別に配線も守る)。
 * 実 context を軽量に組む [ApplicationContextRunner] で、@ConditionalOnProperty の有効/無効・fail-fast・
 * URL 導出の実行までを Testcontainers 無しで確認する(JWKS 取得は初回検証まで遅延するのでネットワークには出ない)。
 */
class AccessJwtWiringTest {

    private val runner =
        ApplicationContextRunner().withUserConfiguration(AccessJwtConfig::class.java)

    @Test
    fun registers_filter_and_processor_when_aud_and_team_domain_are_set() {
        runner
            .withPropertyValues(
                "rss-watch.access.team-domain=myteam.cloudflareaccess.com",
                "rss-watch.access.aud=aud-tag-123",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JWTProcessor::class.java)
                assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
            }
    }

    @Test
    fun accepts_team_domain_with_https_scheme() {
        // https:// 付きでもホスト正規化されて起動できる(URL 導出が例外を投げない)
        runner
            .withPropertyValues(
                "rss-watch.access.team-domain=https://myteam.cloudflareaccess.com",
                "rss-watch.access.aud=aud-tag-123",
            )
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun registers_nothing_when_aud_is_not_set() {
        // aud 未設定なら feature 一式が無効(既存挙動を壊さない)
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(JWTProcessor::class.java)
        }
    }

    @Test
    fun fails_fast_when_aud_is_set_but_team_domain_is_missing() {
        runner
            .withPropertyValues("rss-watch.access.aud=aud-tag-123")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasStackTraceContaining("team-domain")
            }
    }

    @Test
    fun fails_fast_when_team_domain_has_a_path() {
        // パス付き(素のホストでない)は issuer/JWKS URL が壊れるため起動時に弾く
        runner
            .withPropertyValues(
                "rss-watch.access.team-domain=myteam.cloudflareaccess.com/oops",
                "rss-watch.access.aud=aud-tag-123",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("bare host")
            }
    }
}
