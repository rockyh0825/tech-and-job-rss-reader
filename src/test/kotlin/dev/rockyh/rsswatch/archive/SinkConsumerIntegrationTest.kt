package dev.rockyh.rsswatch.archive

import dev.rockyh.rsswatch.archive.infrastructure.RssItemRepository
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

// initial-delay を大きくして、テスト中に @Scheduled の巡回(実フィードへのアクセス)が走らないようにする
@SpringBootTest(properties = ["rss-watch.fetch.initial-delay-ms=3600000"])
@EmbeddedKafka(
    partitions = 1,
    topics = ["rss.items"],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
class SinkConsumerIntegrationTest {

    companion object {
        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${tempDir.resolve("sink-it.db")}" }
        }
    }

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    lateinit var repository: RssItemRepository

    @Autowired
    lateinit var listenerRegistry: KafkaListenerEndpointRegistry

    private fun rssItem(guid: String, category: String = "tech"): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = category,
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = Instant.now(),
            fetchedAt = Instant.now(),
            keywords = listOf("Kotlin"),
        )

    private fun publish(item: RssItem) {
        kafkaTemplate.send("rss.items", item.feedName, item.toJson()).get()
    }

    private fun storedGuids(category: ItemCategory = ItemCategory.TECH): List<String> =
        repository.itemsByCategory(category, days = 7).map { it.guid }

    /** [condition] が true になるまで最大 [timeoutMs] ポーリングする。 */
    private fun await(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    @Test
    fun persists_published_items_into_the_database() {
        val item = rssItem("persist-1")

        publish(item)

        await { storedGuids().contains("persist-1") }
        val stored = repository.itemsByCategory(ItemCategory.TECH, days = 7).single { it.guid == "persist-1" }
        assertEquals(item, stored)
    }

    @Test
    fun does_not_increase_row_count_when_same_guid_is_redelivered() {
        val item = rssItem("dup-1")

        publish(item)
        publish(item)
        publish(rssItem("dup-marker"))

        // marker まで処理が進んだ時点で、同 guid は 1 行のままであること
        await { storedGuids().contains("dup-marker") }
        assertEquals(1, storedGuids().count { it == "dup-1" })
    }

    @Test
    fun catches_up_messages_published_while_sink_was_stopped() {
        val container = listenerRegistry.getListenerContainer("sink")!!
        container.stop()
        try {
            publish(rssItem("offline-1"))
            Thread.sleep(1000)
            assertEquals(0, storedGuids().count { it == "offline-1" })
        } finally {
            container.start()
        }

        await { storedGuids().contains("offline-1") }
    }

    @Test
    fun skips_malformed_json_and_stores_subsequent_valid_items() {
        kafkaTemplate.send("rss.items", "feed", "{ this is not valid json").get()
        publish(rssItem("after-malformed"))

        await { storedGuids().contains("after-malformed") }
    }
}
