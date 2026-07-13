package dev.rockyh.rsswatch.notify.domain

import java.time.Instant

/** 技術ごとの最終紹介日時の記録・照会の抽象(実装は infrastructure の FeaturedTechRepository)。 */
interface FeaturedTechStore {

    /** 紹介済みの技術キーワードと最終紹介日時のマップを返す(未紹介の技術は含まれない)。 */
    fun lastFeaturedAt(): Map<String, Instant>

    /** [keywords] を現在時刻で紹介済みとして記録する(既出のキーワードは最新時刻へ上書き)。 */
    fun markFeatured(keywords: List<String>)
}
