package dev.rockyh.rsswatch.archive.presentation

import dev.rockyh.rsswatch.archive.application.StoreItemsUseCase
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * topic `rss.items` をバッチで消費して DB へ冪等書き込みする(groupId = "sink")。
 *
 * - オフセットはリスナー正常終了後にコミットされるため、DB 書き込み失敗時は
 *   コミットされず再配信で回復する(at-least-once。design.md Error Scenario 3)
 * - パースできないメッセージは poison にならないよう警告ログを出して捨てる
 *   (再配信しても直らないため)
 */
@Component
class SinkConsumer(private val storeItemsUseCase: StoreItemsUseCase) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        id = "sink",
        topics = ["\${rss-watch.topic}"],
        containerFactory = "batchKafkaListenerContainerFactory",
    )
    fun onMessages(messages: List<String>) {
        val items = messages.mapNotNull(::parseOrNull)
        storeItemsUseCase.store(items)
    }

    private fun parseOrNull(json: String): RssItem? =
        try {
            RssItem.fromJson(json)
        } catch (e: Exception) {
            log.warn("skipping malformed message: {}", json.take(200), e)
            null
        }
}
