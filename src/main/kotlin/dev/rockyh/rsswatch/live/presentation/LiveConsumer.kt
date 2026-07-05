package dev.rockyh.rsswatch.live.presentation

import dev.rockyh.rsswatch.live.application.SseBroadcaster
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * topic `rss.items` を 1 件ずつ即時消費して SSE へ中継する(groupId = "live")。
 * DB には触らない。sink とは consumer group が独立しているため、
 * 片方の停止がもう片方に影響しない(要件 4.2)。
 */
@Component
class LiveConsumer(private val sseBroadcaster: SseBroadcaster) {

    @KafkaListener(
        id = "live",
        topics = ["\${rss-watch.topic}"],
        containerFactory = "liveKafkaListenerContainerFactory",
    )
    fun onMessage(message: String) {
        sseBroadcaster.broadcast(message)
    }
}
