package dev.rockyh.rsswatch.shared.config

import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.http.client.HttpClientProperties
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * application.yml の設定が、Spring Boot 自身のプロパティクラスに**実際に束縛される**ことを検証する。
 *
 * 値をベタ書きで比較するのではなく Boot の [TaskSchedulingProperties] / [HttpClientProperties] に
 * バインドして確かめるのが要点。プロパティ名を綴り間違えるとどこにも束縛されず既定値のままになるため、
 * 「yml に書いたのに効いていない」を検出できる。
 */
class ApplicationYamlSchedulingAndHttpTest {

    private val binder: Binder = Binder.get(environmentWithApplicationYaml())

    @Test
    fun scheduler_pool_has_more_than_one_thread_so_fetch_and_digest_do_not_share_one() {
        // 既定は 1 本。FetchScheduler(15分毎の巡回)と DigestScheduler が同じスレッドを共有すると、
        // Claude や Discord への 1 本がハングした間 RSS 巡回ごと止まる
        val properties = binder.bind("spring.task.scheduling", TaskSchedulingProperties::class.java).get()

        assertTrue(properties.pool.size >= 2, "スケジューラのスレッドが ${properties.pool.size} 本しかない")
    }

    @Test
    fun http_client_has_finite_connect_and_read_timeouts() {
        // Boot 3.5 の自動構成 RestClient.Builder は既定でタイムアウトを持たない(= 無限に待つ)。
        // ClaudeSummarizer と DiscordWebhookClient が握るので、必ず有限値を入れておく
        val properties = binder.bind("spring.http.client", HttpClientProperties::class.java).get()

        assertNotNull(properties.connectTimeout, "connect-timeout が未設定(無限待ち)")
        assertNotNull(properties.readTimeout, "read-timeout が未設定(無限待ち)")
        assertTrue(properties.connectTimeout > Duration.ZERO)
        assertTrue(properties.readTimeout > Duration.ZERO)
    }

    @Test
    fun http_read_timeout_leaves_room_for_a_claude_summary() {
        // 短すぎると要約が毎回タイムアウトする。要約 1 件ぶんの生成が収まる長さを確保する
        val properties = binder.bind("spring.http.client", HttpClientProperties::class.java).get()

        assertTrue(
            properties.readTimeout >= Duration.ofSeconds(20),
            "read-timeout ${properties.readTimeout} は Claude の要約には短すぎる",
        )
    }

    // 「プロパティ名を綴り間違えると既定値に戻る」ことそのものを確かめるテストは置かない。
    // 存在しない prefix を Binder に渡しても、検証されるのは Spring Boot 自身の既定値であって
    // このリポジトリのコードは一行も通らない(yml を何に変えても永久に緑)。
    // 綴り誤りの検出は、上の 3 テストが実際の prefix で束縛できていることで担保する。

    /** クラスパス上の application.yml だけを載せた Environment を組み立てる。 */
    private fun environmentWithApplicationYaml(): StandardEnvironment {
        val resource = ClassPathResource("application.yml")
        val sources: List<PropertySource<*>> = YamlPropertySourceLoader().load("application.yml", resource)
        val environment = StandardEnvironment()
        // 既定の system property / 環境変数を外し、application.yml の内容だけを見る
        environment.propertySources.forEach { environment.propertySources.remove(it.name) }
        environment.propertySources.addLast(PropertiesPropertySource("empty", java.util.Properties()))
        sources.forEach { environment.propertySources.addFirst(it) }
        return environment
    }
}
