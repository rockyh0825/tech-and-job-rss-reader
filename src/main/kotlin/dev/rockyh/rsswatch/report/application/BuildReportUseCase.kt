package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.springframework.stereotype.Service

/** 求人で言及された技術 1 つ分のクロスセクション(その技術の記事を添える)。 */
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
 * 「求人で言及されている技術」と「その技術の記事」をクロスリンクしたレポートを組み立てる。
 * クロスセクションは言及求人数の降順(ArchiveQueryPort の並びを保持)。
 */
@Service
class BuildReportUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val postedGuidQueryPort: PostedGuidQueryPort,
) {

    fun build(days: Int): Report {
        val crossSections =
            archiveQueryPort.techRanking(days).map { mention ->
                CrossSection(
                    keyword = mention.keyword,
                    mentionCount = mention.mentionCount,
                    articles =
                        archiveQueryPort.itemsByKeyword(mention.keyword, ItemCategory.TECH, days),
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
