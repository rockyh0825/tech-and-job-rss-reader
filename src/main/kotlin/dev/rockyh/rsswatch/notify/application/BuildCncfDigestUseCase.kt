package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.notify.ConditionalOnCncfNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.CncfCandidate
import dev.rockyh.rsswatch.notify.domain.CncfDigestEntry
import dev.rockyh.rsswatch.notify.domain.CncfDigestPublisher
import dev.rockyh.rsswatch.notify.domain.CncfDigestSelectionPolicy
import dev.rockyh.rsswatch.notify.domain.CncfProjectMatcher
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import dev.rockyh.rsswatch.notify.domain.Summarizer
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * CNCF ダイジェスト(issue #46)の編成:CNCF フィードの新着記事に成熟度バッジを付けて投稿する。
 *
 * - 取得は archive の [ArchiveQueryPort.itemsByCategory] を再利用(`category = "cncf"`。DB は読み取りのみ)
 * - 各記事のタイトル + 概要を [CncfProjectMatcher] で照合し、言及された CNCF プロジェクトと成熟度を検出する
 * - 並び順は [CncfDigestSelectionPolicy]:成熟度の低いプロジェクトに言及する記事が先
 *   (graduated 前を早期に掴む)、tier 内は新着順、上限 [maxArticles] 件(初回のバックログ氾濫防止)
 * - 一度通知した記事は二度と載せない(通知済み guid 全件を除外。既存ダイジェストと同じ永続的な重複排除。
 *   テーブルは共有だがカテゴリが排他のため相互干渉しない)
 * - 候補 0 件は導線(サイト + CNCF 一覧)だけを投稿 / 要約失敗は要約なしでフォールバック / サムネイル解決失敗は画像なしでフォールバック
 * - 投稿は記事ごとに 1 通ずつ行われるため、実際に投稿できた記事だけを markPosted する
 */
@Service
@ConditionalOnCncfNotifyEnabled
class BuildCncfDigestUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val summarizer: Summarizer,
    private val webhookClient: CncfDigestPublisher,
    private val postedGuidRepository: PostedGuidStore,
    private val thumbnailResolver: ThumbnailResolver,
    @Value("\${rss-watch.notify.cncf.max-articles:8}") private val maxArticles: Int,
    @Value("\${rss-watch.notify.cncf.window-days:7}") private val windowDays: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val matcher = CncfProjectMatcher()

    private val selectionPolicy = CncfDigestSelectionPolicy()

    fun run() {
        // 過去に一度でも通知した記事は二度と載せない(EPOCH 起点=通知済み全件を照会)。
        val alreadyPosted = postedGuidRepository.postedGuids(Instant.EPOCH)

        val candidates =
            archiveQueryPort
                .itemsByCategory(ItemCategory.CNCF, windowDays)
                .filterNot { it.guid in alreadyPosted }
                .map { CncfCandidate(it, matcher.match("${it.title} ${it.summary}")) }

        val selected = selectionPolicy.select(candidates, maxArticles)
        if (selected.isEmpty()) {
            // 候補が無い日も無言にはせず、導線(サイト + CNCF 一覧)だけを届ける。記事が無いので
            // markPosted の対象も無く、失敗しても翌日また試みるだけ(warn に留めて自己回復に任せる)。
            val outcome = webhookClient.postCtaOnly()
            if (outcome.failure == null) {
                log.info("no CNCF digest candidates; posted the links only")
            } else {
                log.warn("no CNCF digest candidates; failed to post the links", outcome.failure)
            }
            return
        }

        // 要約・サムネイル解決(外部 API)は選抜後の記事だけに行う。
        val entries = selected.map { CncfDigestEntry(toArticle(it), it.mentions) }

        // 記事ごとに 1 通ずつ投稿されるため、途中で失敗すると「一部だけ投稿済み」になり得る。
        // 実際に投稿できた記事だけを通知済みにすることで、投稿済みの記事は翌日に重複せず、
        // 未投稿の記事は次回の巡回で改めて候補に上がる。
        val outcome = webhookClient.post(entries)
        if (outcome.postedGuids.isNotEmpty()) {
            postedGuidRepository.markPosted(outcome.postedGuids)
        }
        if (outcome.failure == null) {
            log.info("posted CNCF digest: {} articles", outcome.postedGuids.size)
        } else {
            log.warn(
                "failed to post CNCF digest; posted {} of {} articles, the rest will retry next schedule",
                outcome.postedGuids.size,
                entries.size,
                outcome.failure,
            )
        }
    }

    /**
     * 記事 1 件を表示内容に変換する。要約失敗時は summary=null(見出しごと省くフォールバック)、
     * サムネイルを解決できなければ thumbnailUrl=null(画像なしのフォールバック)。
     */
    private fun toArticle(candidate: CncfCandidate): DigestArticle =
        DigestArticle(
            guid = candidate.item.guid,
            title = candidate.item.title,
            url = candidate.item.url,
            summary = summarizer.summarize(candidate.item.title, candidate.item.summary).getOrNull(),
            thumbnailUrl = thumbnailResolver.resolve(candidate.item.url),
        )
}
