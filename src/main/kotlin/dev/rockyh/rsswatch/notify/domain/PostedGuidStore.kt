package dev.rockyh.rsswatch.notify.domain

import java.time.Instant

/** 投稿済み guid の記録・照会の抽象(実装は infrastructure の PostedGuidRepository)。 */
interface PostedGuidStore {

    /** [since] 以降に投稿済みとして記録された guid の集合を返す。 */
    fun postedGuids(since: Instant): Set<String>

    /** [guids] を投稿済みとして記録する(冪等)。 */
    fun markPosted(guids: List<String>)
}
