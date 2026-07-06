package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestEntry
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.DigestSelectionPolicy
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * デイリーダイジェストの編成: 取得 → 選抜 → 要約 → 投稿 → 投稿済み記録(design.md「BuildDigestUseCase」参照)。
 *
 * - 取得は archive の [ArchiveQueryPort.itemsByCategory] を再利用(DB は読み取りのみ・要件 4.1)
 * - 候補 0 件は投稿せずスキップ(要件 1.4)
 * - 要約失敗は要約なしでフォールバック(要件 2.2)
 * - Webhook 失敗時は markPosted しない(翌日ジョブで再挑戦・要件 3.2)
 */
@Service
@ConditionalOnNotifyEnabled
class BuildDigestUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val policy: DigestSelectionPolicy,
    private val summarizer: Summarizer,
    private val webhookClient: DigestPublisher,
    private val postedGuidRepository: PostedGuidStore,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${rss-watch.notify.limit:5}") private val limit: Int,
    @Value("\${rss-watch.notify.posted-lookback-days:2}") private val postedLookbackDays: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        val candidates = archiveQueryPort.itemsByCategory(ItemCategory.TECH, TECH_WINDOW_DAYS)
        val since = clock.instant().minus(Duration.ofDays(postedLookbackDays))
        val alreadyPosted = postedGuidRepository.postedGuids(since)

        val selected = policy.select(candidates, limit, alreadyPosted)
        if (selected.isEmpty()) {
            log.info("no digest candidates; skipping post")
            return
        }

        val entries = selected.map(::toEntry)
        val result = webhookClient.post(entries)
        if (result.isSuccess) {
            postedGuidRepository.markPosted(selected.map { it.guid })
            log.info("posted daily digest with {} items", selected.size)
        } else {
            log.warn("failed to post daily digest; will retry next schedule", result.exceptionOrNull())
        }
    }

    /** 記事 1 件を表示内容に変換する。要約失敗時は summary=null(要件 2.2 のフォールバック)。 */
    private fun toEntry(item: RssItem): DigestEntry =
        DigestEntry(
            title = item.title,
            url = item.url,
            summary = summarizer.summarize(item.title, item.summary).getOrNull(),
            keywords = item.keywords,
        )

    companion object {
        /** 取得窓は日単位(直近 24h)。ArchiveQueryPort の既存メソッドに合わせる。 */
        private const val TECH_WINDOW_DAYS = 1
    }
}
