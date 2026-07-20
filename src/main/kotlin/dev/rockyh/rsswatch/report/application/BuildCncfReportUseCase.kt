package dev.rockyh.rsswatch.report.application

import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.CncfMatchPort
import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import org.springframework.stereotype.Service

/** CNCF レポートの記事 1 件。[mentions] は成熟度の低い順に整列済み(言及なしなら空)。 */
data class CncfReportArticle(
    val item: RssItem,
    val mentions: List<CncfMention>,
) {
    /** 記事の tier。言及プロジェクトのうち最も低い成熟度(言及なしは null = 最後尾)。 */
    val tier: CncfMaturity? = mentions.minOfOrNull { it.maturity }
}

/** GET /api/cncf のレスポンス。 */
data class CncfReport(
    val articles: List<CncfReportArticle>,
)

/**
 * Web UI 向けの CNCF レポートを組み立てる。CNCF ダイジェスト(Discord)と同じ見せ方を
 * ブラウザで再現する: 記事に CNCF プロジェクト言及と成熟度を付与し、同じ全順序で並べる。
 *
 * - 取得は archive の [ArchiveQueryPort.itemsByCategory](`category = "cncf"`)、照合は [CncfMatchPort]
 * - 並び順: tier 昇順・言及なし最後尾(sandbox → incubating → graduated → 言及なし)
 *   → publishedAt 降順(null は最古扱い)→ guid 昇順(完全決定性の担保)
 * - ダイジェストと違い件数上限は設けない(push と違い閲覧はスクロールでき、窓(days)が上限の役割を果たす)
 * - Discord Webhook の設定有無とは無関係に常に有効(通知機能と独立)
 */
@Service
class BuildCncfReportUseCase(
    private val archiveQueryPort: ArchiveQueryPort,
    private val cncfMatchPort: CncfMatchPort,
) {

    private val priorityOrder =
        compareBy<CncfReportArticle, CncfMaturity?>(nullsLast(naturalOrder())) { it.tier }
            .thenByDescending { it.item.publishedAt }
            .thenBy { it.item.guid }

    fun build(days: Int): CncfReport {
        val articles =
            archiveQueryPort
                .itemsByCategory(ItemCategory.CNCF, days)
                .map { CncfReportArticle(it, cncfMatchPort.match("${it.title} ${it.summary}")) }
                .sortedWith(priorityOrder)
        return CncfReport(articles)
    }
}
