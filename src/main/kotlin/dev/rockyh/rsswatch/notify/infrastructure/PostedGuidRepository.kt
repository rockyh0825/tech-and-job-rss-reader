package dev.rockyh.rsswatch.notify.infrastructure

import dev.hsbrysk.kuery.core.KueryBlockingClient
import dev.hsbrysk.kuery.core.list
import dev.rockyh.rsswatch.notify.ConditionalOnAnyNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 投稿済み guid の記録と照会(日跨ぎの二重投稿防止)。方言依存(ON CONFLICT)をこのクラスに閉じ込める。
 * スキーマは Flyway(V2__notify_posted_guids.sql)が唯一の正本。
 *
 * - posted_at は TIMESTAMPTZ。[Instant] は UTC の [OffsetDateTime] に変換してバインドする
 *   (RssItemRepository と同じ作法)
 * - [markPosted] は `INSERT ... ON CONFLICT (guid) DO NOTHING` で同 guid の再投入を無害化する
 */
@Repository
@ConditionalOnAnyNotifyEnabled
class PostedGuidRepository(
    private val kueryClient: KueryBlockingClient,
    private val clock: Clock = Clock.systemUTC(),
) : PostedGuidStore {

    /** [since] 以降に投稿済みとして記録された guid の集合を返す。 */
    override fun postedGuids(since: Instant): Set<String> {
        val cutoff = toUtcOffset(since)
        return kueryClient
            .sql {
                +"SELECT guid FROM notify_posted WHERE posted_at >= $cutoff"
            }.list<GuidRow>()
            .map { it.guid }
            .toSet()
    }

    /** [guids] を現在時刻で投稿済みとして記録する(既存 guid は posted_at を上書きしない)。 */
    @Transactional
    override fun markPosted(guids: List<String>) {
        val postedAt = toUtcOffset(clock.instant())
        for (guid in guids) {
            kueryClient
                .sql {
                    +"""
                    INSERT INTO notify_posted (guid, posted_at) VALUES ($guid, $postedAt)
                    ON CONFLICT (guid) DO NOTHING
                    """
                }.rowsUpdated()
        }
    }

    private fun toUtcOffset(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private data class GuidRow(val guid: String)
}
