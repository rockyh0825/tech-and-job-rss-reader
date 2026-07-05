package dev.rockyh.rsswatch.fetch.infrastructure

import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

class KafkaItemPublisherTest {

    private val item =
        RssItem(
            guid = "guid-1",
            feedName = "Zenn",
            category = "tech",
            title = "Kotlinの話",
            url = "https://example.com/1",
            summary = "概要",
            publishedAt = null,
            fetchedAt = Instant.parse("2026-07-05T12:00:00Z"),
            keywords = listOf("Kotlin"),
        )

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate =
        Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>

    @Test
    fun publishes_item_json_to_topic_with_feed_name_as_key() {
        val expectedJson = item.toJson()
        Mockito.`when`(kafkaTemplate.send("rss.items", "Zenn", expectedJson))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))
        val publisher = KafkaItemPublisher(kafkaTemplate, topic = "rss.items")

        publisher.publish(item)

        Mockito.verify(kafkaTemplate).send("rss.items", "Zenn", expectedJson)
    }

    @Test
    fun uses_configured_topic_name() {
        val expectedJson = item.toJson()
        Mockito.`when`(kafkaTemplate.send("custom.topic", "Zenn", expectedJson))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))
        val publisher = KafkaItemPublisher(kafkaTemplate, topic = "custom.topic")

        publisher.publish(item)

        Mockito.verify(kafkaTemplate).send("custom.topic", "Zenn", expectedJson)
    }
}
