package dev.rockyh.rsswatch.live

import dev.rockyh.rsswatch.live.application.SseBroadcaster
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

// initial-delay を大きくして、テスト中に @Scheduled の巡回(実フィードへのアクセス)が走らないようにする
@SpringBootTest(properties = ["rss-watch.fetch.initial-delay-ms=3600000"])
@EmbeddedKafka(
    partitions = 1,
    topics = ["rss.items"],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
class LiveConsumerIntegrationTest {

    companion object {
        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${tempDir.resolve("live-it.db")}" }
        }
    }

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    lateinit var broadcaster: SseBroadcaster

    @Autowired
    lateinit var listenerRegistry: KafkaListenerEndpointRegistry

    /** send された SSE イベントのデータを記録する emitter。 */
    private class RecordingEmitter : SseEmitter(0L) {
        val sentData = mutableListOf<String>()

        @Synchronized
        override fun send(builder: SseEventBuilder) {
            sentData +=
                builder
                    .build()
                    .map { it.data }
                    .filterIsInstance<String>()
                    .joinToString("")
        }

        @Synchronized
        fun snapshot(): List<String> = sentData.toList()
    }

    private fun rssItem(guid: String): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = "tech",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = Instant.now(),
            fetchedAt = Instant.now(),
            keywords = listOf("Kotlin"),
        )

    /** [condition] が true になるまで最大 [timeoutMs] ポーリングする。 */
    private fun await(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    @BeforeEach
    fun waitForLiveAssignment() {
        // auto-offset-reset=latest のため、パーティション割り当て前に publish すると取りこぼす
        val container = listenerRegistry.getListenerContainer("live")!!
        ContainerTestUtils.waitForAssignment(container, 1)
    }

    @Test
    fun delivers_published_item_to_connected_sse_clients_immediately() {
        val emitter = RecordingEmitter()
        broadcaster.register(emitter)
        val item = rssItem("live-1")

        kafkaTemplate.send("rss.items", item.feedName, item.toJson()).get()

        await { emitter.snapshot().any { it.contains("live-1") } }
    }

    @Test
    fun keeps_delivering_while_sink_consumer_is_stopped() {
        val sink = listenerRegistry.getListenerContainer("sink")!!
        sink.stop()
        try {
            val emitter = RecordingEmitter()
            broadcaster.register(emitter)
            val item = rssItem("live-while-sink-down")

            kafkaTemplate.send("rss.items", item.feedName, item.toJson()).get()

            await { emitter.snapshot().any { it.contains("live-while-sink-down") } }
        } finally {
            sink.start()
        }
    }
}
