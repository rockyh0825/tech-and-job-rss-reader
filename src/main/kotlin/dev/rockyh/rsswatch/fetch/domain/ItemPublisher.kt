package dev.rockyh.rsswatch.fetch.domain

import dev.rockyh.rsswatch.shared.contract.RssItem

/** 抽出済みアイテムをパイプラインへ送るアダプタの抽象(実装: fetch/infrastructure の KafkaItemPublisher)。 */
interface ItemPublisher {

    /** アイテムを publish する。key = フィード名の規約は実装側が担う。送信は非同期でよく、失敗時の扱い(ログして次周期に任せる等)も実装側が担う。 */
    fun publish(item: RssItem)
}
