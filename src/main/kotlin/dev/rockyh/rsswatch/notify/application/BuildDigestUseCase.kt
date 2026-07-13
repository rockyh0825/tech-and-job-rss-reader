package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.DigestSelectionPolicy
import dev.rockyh.rsswatch.notify.domain.FeaturedTechStore
import dev.rockyh.rsswatch.notify.domain.NotifyInterests
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.notify.domain.TechCandidate
import dev.rockyh.rsswatch.notify.domain.TechDigest
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * デイリーダイジェストの編成:「求人で言及された技術」×「その技術の記事」を組み立てて投稿する。
 *
 * - 取得は archive の [ArchiveQueryPort.techRanking] / [ArchiveQueryPort.itemsByKeyword] を再利用
 *   (report の crossSection と同じ組み立て。DB は読み取りのみ)
 * - 候補はランキング全件 + ランキング外の興味技術(求人言及 0 件でも記事があれば載せる)
 * - 優先順位は [DigestSelectionPolicy]:興味技術を先頭に、最近紹介した技術を後回し(ローテーション)、
 *   同着は求人言及数の多い順。上位 [techLimit] 件、各技術につき新しい記事を [articlesPerTech] 件まで載せる
 * - 一度通知した記事は二度と載せない(通知済み guid 全件を除外。永続的な重複排除)
 * - 同じ記事が複数の技術に紐づく場合もダイジェスト内では 1 度だけ載せる(セクション横断で重複排除)
 * - 候補 0 件は投稿せずスキップ / 要約失敗は要約なしでフォールバック / Webhook 失敗時は
 *   markPosted・markFeatured しない
 */
@Service
@ConditionalOnNotifyEnabled
class BuildDigestUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val summarizer: Summarizer,
    private val webhookClient: DigestPublisher,
    private val postedGuidRepository: PostedGuidStore,
    private val interests: NotifyInterests,
    private val featuredTechStore: FeaturedTechStore,
    @Value("\${rss-watch.notify.tech-limit:3}") private val techLimit: Int,
    @Value("\${rss-watch.notify.articles-per-tech:3}") private val articlesPerTech: Int,
    @Value("\${rss-watch.notify.window-days:7}") private val windowDays: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val selectionPolicy = DigestSelectionPolicy()

    fun run() {
        // 過去に一度でも通知した記事は二度と載せない(EPOCH 起点=通知済み全件を照会)。
        val alreadyPosted = postedGuidRepository.postedGuids(Instant.EPOCH)
        val shown = mutableSetOf<String>()

        val digests =
            selectionPolicy
                .prioritize(collectCandidates())
                .asSequence()
                .mapNotNull { candidate ->
                    val articles =
                        archiveQueryPort
                            .itemsByKeyword(candidate.keyword, ItemCategory.TECH, windowDays)
                            .filterNot { it.guid in alreadyPosted || it.guid in shown }
                            .take(articlesPerTech)
                    if (articles.isEmpty()) return@mapNotNull null
                    articles.forEach { shown += it.guid }
                    TechDigest(candidate.keyword, candidate.mentionCount, articles.map(::toArticle), candidate.interested)
                }
                .take(techLimit)
                .toList()

        if (digests.isEmpty()) {
            log.info("no digest candidates; skipping post")
            return
        }

        val result = webhookClient.post(digests)
        if (result.isSuccess) {
            postedGuidRepository.markPosted(shown.toList())
            featuredTechStore.markFeatured(digests.map { it.keyword })
            log.info("posted daily digest: {} techs, {} articles", digests.size, shown.size)
        } else {
            log.warn("failed to post daily digest; will retry next schedule", result.exceptionOrNull())
        }
    }

    /** 求人言及ランキング全件 + ランキング外の興味技術(言及 0 件)を候補にする。 */
    private fun collectCandidates(): List<TechCandidate> {
        val lastFeaturedAt = featuredTechStore.lastFeaturedAt()
        val ranking = archiveQueryPort.techRanking(windowDays)
        val rankedKeywords = ranking.map { it.keyword }.toSet()
        val rankedCandidates =
            ranking.map {
                TechCandidate(it.keyword, it.mentionCount, interests.isInterested(it.keyword), lastFeaturedAt[it.keyword])
            }
        val unrankedInterests =
            (interests.keywords - rankedKeywords).map {
                TechCandidate(it, mentionCount = 0, interested = true, lastFeaturedAt = lastFeaturedAt[it])
            }
        return rankedCandidates + unrankedInterests
    }

    /** 記事 1 件を表示内容に変換する。要約失敗時は summary=null(見出しごと省くフォールバック)。 */
    private fun toArticle(item: RssItem): DigestArticle =
        DigestArticle(
            title = item.title,
            url = item.url,
            summary = summarizer.summarize(item.title, item.summary).getOrNull(),
        )
}
