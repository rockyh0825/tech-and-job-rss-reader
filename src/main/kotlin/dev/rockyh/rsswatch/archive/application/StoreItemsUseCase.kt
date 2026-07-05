package dev.rockyh.rsswatch.archive.application

import dev.rockyh.rsswatch.archive.domain.ItemStore
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 受信した item のバッチを冪等に永続化する(要件 3.1, 3.2)。 */
@Service
class StoreItemsUseCase(private val itemStore: ItemStore) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** バッチを保存し、新規に挿入された件数を返す。 */
    fun store(items: List<RssItem>): Int {
        if (items.isEmpty()) return 0
        val inserted = itemStore.insertIgnore(items)
        log.info("stored {} new items ({} received)", inserted, items.size)
        return inserted
    }
}
