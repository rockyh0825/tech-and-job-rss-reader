package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.springframework.stereotype.Service

/** 注目技術(記事で言及の多い技術)1 つ分のクロスセクション(その技術の記事を添える)。 */
data class CrossSection(
    val keyword: String,
    val mentionCount: Int,
    val articles: List<RssItem>,
)

/** GET /api/report のレスポンス(要件 5.1)。[postedGuids] はレスポンス中の記事のうち Discord 配信済みのもの。 */
data class Report(
    val crossSections: List<CrossSection>,
    val techArticles: List<RssItem>,
    val jobPostings: List<RssItem>,
    val postedGuids: Set<String>,
)

/**
 * 「記事で言及の多い注目技術」と「その技術の記事」をクロスリンクしたレポートを組み立てる。
 * クロスセクションは言及記事数の降順(ArchiveQueryPort の並びを保持)。
 */
@Service
class BuildReportUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val postedGuidQueryPort: PostedGuidQueryPort,
) {

    fun build(days: Int): Report {
        val ranking = archiveQueryPort.techRanking(ItemCategory.TECH, days)
        // キーワードごとの個別クエリは N+1 になるため、全キーワード分を一括で取得する
        val articlesByKeyword =
            archiveQueryPort.itemsByKeywords(ranking.map { it.keyword }, ItemCategory.TECH, days)
        val crossSections =
            ranking.map { mention ->
                CrossSection(
                    keyword = mention.keyword,
                    mentionCount = mention.mentionCount,
                    articles = articlesByKeyword[mention.keyword].orEmpty(),
                )
            }
        val techArticles = archiveQueryPort.itemsByCategory(ItemCategory.TECH, days)
        val jobPostings = archiveQueryPort.itemsByCategory(ItemCategory.JOBS, days)
        val guidsInReport =
            buildSet {
                crossSections.forEach { section -> section.articles.mapTo(this) { it.guid } }
                techArticles.mapTo(this) { it.guid }
                jobPostings.mapTo(this) { it.guid }
            }
        return Report(
            crossSections = crossSections,
            techArticles = techArticles,
            jobPostings = jobPostings,
            postedGuids = postedGuidQueryPort.postedIn(guidsInReport),
        )
    }
}
