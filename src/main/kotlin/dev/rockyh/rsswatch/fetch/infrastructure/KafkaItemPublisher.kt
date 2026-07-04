package dev.rockyh.rsswatch.fetch.infrastructure

import dev.rockyh.rsswatch.fetch.domain.ItemPublisher
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * topic `rss.items` へ JSON で publish する [ItemPublisher] 実装。
 * key = フィード名(パーティショニングの観察のため。tech.md 決定 4)。
 * 送信失敗は producer の再試行に任せ、最終失敗はログのみ(次周期の巡回で再度拾える)。
 */
@Component
class KafkaItemPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${rss-watch.topic:rss.items}") private val topic: String = "rss.items",
) : ItemPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(item: RssItem) {
        kafkaTemplate.send(topic, item.feedName, item.toJson()).whenComplete { _, exception ->
            if (exception != null) {
                log.warn("publish に失敗しました: guid={} feed={}", item.guid, item.feedName, exception)
            }
        }
    }
}
