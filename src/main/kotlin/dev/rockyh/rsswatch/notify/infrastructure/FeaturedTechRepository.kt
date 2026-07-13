package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.core.list
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.FeaturedTechStore
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 技術ごとの最終紹介日時の記録と照会(ダイジェストのローテーション用)。
 * 方言依存(ON CONFLICT)をこのクラスに閉じ込める。
 * スキーマは Flyway(V3__notify_featured_techs.sql)が唯一の正本。
 *
 * - last_featured_at は TIMESTAMPTZ。[Instant] は UTC の [OffsetDateTime] に変換してバインドする
 *   (PostedGuidRepository と同じ作法)
 * - [markFeatured] は `INSERT ... ON CONFLICT (keyword) DO UPDATE` で最新時刻へ上書きする
 *   (notify_posted の DO NOTHING と違い、「最後に紹介したのはいつか」を追跡するのが目的)
 */
@Repository
@ConditionalOnNotifyEnabled
class FeaturedTechRepository(
    private val kueryClient: KueryBlockingClient,
    private val clock: Clock = Clock.systemUTC(),
) : FeaturedTechStore {

    /** 紹介済みの技術キーワードと最終紹介日時のマップを返す。 */
    override fun lastFeaturedAt(): Map<String, Instant> =
        kueryClient
            .sql {
                +"SELECT keyword, last_featured_at FROM notify_featured_techs"
            }.list<FeaturedTechRow>()
            .associate { it.keyword to it.lastFeaturedAt.toInstant() }

    /** [keywords] を現在時刻で紹介済みとして記録する(既出のキーワードは最新時刻へ上書き)。 */
    @Transactional
    override fun markFeatured(keywords: List<String>) {
        val featuredAt = toUtcOffset(clock.instant())
        for (keyword in keywords) {
            kueryClient
                .sql {
                    +"""
                    INSERT INTO notify_featured_techs (keyword, last_featured_at) VALUES ($keyword, $featuredAt)
                    ON CONFLICT (keyword) DO UPDATE SET last_featured_at = EXCLUDED.last_featured_at
                    """
                }.rowsUpdated()
        }
    }

    private fun toUtcOffset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private data class FeaturedTechRow(val keyword: String, val lastFeaturedAt: OffsetDateTime)
}
