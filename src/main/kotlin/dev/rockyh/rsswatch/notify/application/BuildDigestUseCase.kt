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
import dev.rockyh.rsswatch.notify.domain.ThumbnailResolver
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * デイリーダイジェストの編成:「記事で言及の多い注目技術」×「その技術の記事」を組み立てて投稿する。
 *
 * - 取得は archive の [ArchiveQueryPort.techRanking] / [ArchiveQueryPort.itemsByKeyword] を再利用
 *   (report の crossSection と同じ組み立て。DB は読み取りのみ)
 * - 候補はランキング上位 [techPoolSize] 件 + 興味技術(足切りの対象外)+ ランキング外の興味技術
 *   (記事ベースのランキングでは新着記事のある技術は必ずランキング内のため最後は実質空。
 *   ランキング軸を求人に戻した場合の救済として残す)
 * - 優先順位は [DigestSelectionPolicy]:興味技術を先頭に、クールダウン中(直近 [rotationCooldownDays] 日
 *   以内に紹介)の技術を後回し、あとは記事言及数の多い順。上位 [techLimit] 件、各技術につき新しい記事を
 *   [articlesPerTech] 件まで載せる
 * - 一度通知した記事は二度と載せない(通知済み guid 全件を除外。永続的な重複排除)
 * - 同じ記事が複数の技術に紐づく場合もダイジェスト内では 1 度だけ載せる(セクション横断で重複排除)
 * - 候補 0 件はサイト導線だけを投稿 / 要約失敗は要約なしでフォールバック / サムネイル解決失敗は画像なしでフォールバック
 * - 投稿は記事ごとに 1 通ずつ行われるため、実際に投稿できた記事だけを markPosted し、その記事を
 *   含む技術だけを markFeatured する(届かなかった技術はローテーションに乗せず候補に残す)
 */
@Service
@ConditionalOnNotifyEnabled
class BuildDigestUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val summarizer: Summarizer,
    private val webhookClient: DigestPublisher,
    private val postedGuidRepository: PostedGuidStore,
    private val thumbnailResolver: ThumbnailResolver,
    private val interests: NotifyInterests,
    private val featuredTechStore: FeaturedTechStore,
    @Value("\${rss-watch.notify.tech-limit:3}") private val techLimit: Int,
    @Value("\${rss-watch.notify.articles-per-tech:3}") private val articlesPerTech: Int,
    @Value("\${rss-watch.notify.window-days:7}") private val windowDays: Int,
    @Value("\${rss-watch.notify.rotation-cooldown-days:3}") private val rotationCooldownDays: Int,
    @Value("\${rss-watch.notify.tech-pool-size:10}") private val techPoolSize: Int,
    private val clock: Clock = Clock.systemUTC(),
) {

    init {
        require(techPoolSize > 0) { "rss-watch.notify.tech-pool-size must be positive: $techPoolSize" }
        // 設定キーと値のマッピングは application 層の関心なので、domain([DigestSelectionPolicy])の
        // require とは別にここでも設定キー名つきで検証する(起動時 fail-fast のメッセージを分かりやすく)
        require(rotationCooldownDays > 0) {
            "rss-watch.notify.rotation-cooldown-days must be positive: $rotationCooldownDays"
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private val selectionPolicy = DigestSelectionPolicy(rotationCooldownDays)

    fun run() {
        // 過去に一度でも通知した記事は二度と載せない(EPOCH 起点=通知済み全件を照会)。
        val alreadyPosted = postedGuidRepository.postedGuids(Instant.EPOCH)
        val shown = mutableSetOf<String>()

        val digests =
            selectionPolicy
                .prioritize(collectCandidates(), clock.instant())
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
            // 候補が無い日も無言にはせず、サイト導線だけを届ける。記事が無いので markPosted / markFeatured の
            // 対象も無く、失敗しても翌日また試みるだけ(warn に留めて自己回復に任せる)。
            val outcome = webhookClient.postCtaOnly()
            if (outcome.failure == null) {
                log.info("no digest candidates; posted the site link only")
            } else {
                log.warn("no digest candidates; failed to post the site link", outcome.failure)
            }
            return
        }

        // 記事ごとに 1 通ずつ投稿されるため、途中で失敗すると「一部だけ投稿済み」になり得る。
        // 実際に投稿できた記事だけを通知済みにすることで、投稿済みの記事は翌日に重複せず、
        // 未投稿の記事は次回の巡回で改めて候補に上がる。
        val outcome = webhookClient.post(digests)
        if (outcome.postedGuids.isNotEmpty()) {
            postedGuidRepository.markPosted(outcome.postedGuids)
            // 記事が 1 件も届かなかった技術は「紹介した」とは言えないので、ローテーションに乗せず候補に残す。
            // ローテーション記録の一時失敗は配信成功を壊さない(未記録=次回も候補に残るだけで自己回復する)
            runCatching { featuredTechStore.markFeatured(featuredKeywords(digests, outcome.postedGuids)) }
                .onFailure { log.warn("failed to mark featured techs; rotation will catch up next time", it) }
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

    /** 記事を 1 件でも投稿できた技術だけを返す(ローテーションに乗せる対象)。 */
    private fun featuredKeywords(digests: List<TechDigest>, postedGuids: List<String>): List<String> {
        val posted = postedGuids.toSet()
        return digests.filter { digest -> digest.articles.any { it.guid in posted } }.map { it.keyword }
    }

    /** 記事言及ランキング上位 [techPoolSize] 件(興味技術は足切り対象外)+ ランキング外の興味技術(言及 0 件)を候補にする。 */
    private fun collectCandidates(): List<TechCandidate> {
        val lastFeaturedAt = featuredTechStore.lastFeaturedAt()
        val ranking = archiveQueryPort.techRanking(ItemCategory.TECH, windowDays)
        val rankedKeywords = ranking.map { it.keyword }.toSet()
        val rankedCandidates =
            ranking
                // 候補プールの足切り: ランキング上位 techPoolSize 件に絞る。「未紹介だから」という理由
                // だけで言及の少ないマイナー技術が浮上するのを防ぐ。興味技術は足切りの対象外
                // (実言及数を保ったまま候補に残す)
                .filterIndexed { index, mention -> index < techPoolSize || interests.isInterested(mention.keyword) }
                .map {
                    TechCandidate(it.keyword, it.mentionCount, interests.isInterested(it.keyword), lastFeaturedAt[it.keyword])
                }
        val unrankedInterests =
            (interests.keywords - rankedKeywords).map {
                TechCandidate(it, mentionCount = 0, interested = true, lastFeaturedAt = lastFeaturedAt[it])
            }
        return rankedCandidates + unrankedInterests
    }

    /**
     * 記事 1 件を表示内容に変換する。要約失敗時は summary=null(見出しごと省くフォールバック)、
     * サムネイルを解決できなければ thumbnailUrl=null(画像なしのフォールバック)。
     */
    private fun toArticle(item: RssItem): DigestArticle =
        DigestArticle(
            guid = item.guid,
            title = item.title,
            url = item.url,
            summary = summarizer.summarize(item.title, item.summary).getOrNull(),
            thumbnailUrl = thumbnailResolver.resolve(item.url),
        )
}
