package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
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
 * - 求人で言及の多い技術を上位 [techLimit] 件、各技術につき新しい記事を [articlesPerTech] 件まで載せる
 * - 一度通知した記事は二度と載せない(通知済み guid 全件を除外。永続的な重複排除)
 * - 同じ記事が複数の技術に紐づく場合もダイジェスト内では 1 度だけ載せる(セクション横断で重複排除)
 * - 候補 0 件は投稿せずスキップ / 要約失敗は要約なしでフォールバック / Webhook 失敗時は markPosted しない
 */
@Service
@ConditionalOnNotifyEnabled
class BuildDigestUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val summarizer: Summarizer,
    private val webhookClient: DigestPublisher,
    private val postedGuidRepository: PostedGuidStore,
    @Value("\${rss-watch.notify.tech-limit:3}") private val techLimit: Int,
    @Value("\${rss-watch.notify.articles-per-tech:3}") private val articlesPerTech: Int,
    @Value("\${rss-watch.notify.window-days:7}") private val windowDays: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        // 過去に一度でも通知した記事は二度と載せない(EPOCH 起点=通知済み全件を照会)。
        val alreadyPosted = postedGuidRepository.postedGuids(Instant.EPOCH)
        val shown = mutableSetOf<String>()

        val digests =
            archiveQueryPort
                .techRanking(windowDays)
                .asSequence()
                .mapNotNull { mention ->
                    val articles =
                        archiveQueryPort
                            .itemsByKeyword(mention.keyword, ItemCategory.TECH, windowDays)
                            .filterNot { it.guid in alreadyPosted || it.guid in shown }
                            .take(articlesPerTech)
                    if (articles.isEmpty()) return@mapNotNull null
                    articles.forEach { shown += it.guid }
                    TechDigest(mention.keyword, mention.mentionCount, articles.map(::toArticle))
                }
                .take(techLimit)
                .toList()

        if (digests.isEmpty()) {
            log.info("no digest candidates; skipping post")
            return
        }

        // 記事ごとに 1 通ずつ投稿されるため、途中で失敗すると「一部だけ投稿済み」になり得る。
        // 実際に投稿できた記事だけを通知済みにすることで、投稿済みの記事は翌日に重複せず、
        // 未投稿の記事は次回の巡回で改めて候補に上がる。
        val outcome = webhookClient.post(digests)
        if (outcome.postedGuids.isNotEmpty()) {
            postedGuidRepository.markPosted(outcome.postedGuids)
        }
        if (outcome.failure == null) {
            log.info("posted daily digest: {} techs, {} articles", digests.size, outcome.postedGuids.size)
        } else {
            log.warn(
                "failed to post daily digest; posted {} of {} articles, the rest will retry next schedule",
                outcome.postedGuids.size,
                shown.size,
                outcome.failure,
            )
        }
    }

    /** 記事 1 件を表示内容に変換する。要約失敗時は summary=null(見出しごと省くフォールバック)。 */
    private fun toArticle(item: RssItem): DigestArticle =
        DigestArticle(
            guid = item.guid,
            title = item.title,
            url = item.url,
            summary = summarizer.summarize(item.title, item.summary).getOrNull(),
        )
}
