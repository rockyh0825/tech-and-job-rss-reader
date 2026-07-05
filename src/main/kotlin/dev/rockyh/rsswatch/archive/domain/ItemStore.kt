package dev.rockyh.rsswatch.archive.domain

import dev.rockyh.rsswatch.shared.contract.RssItem

/** item の永続化先の抽象(実装は infrastructure の RssItemRepository)。 */
interface ItemStore {

    /** 新規 guid の item のみ挿入し、挿入した件数を返す(既存 guid は無視 = 冪等)。 */
    fun insertIgnore(items: List<RssItem>): Int
}
