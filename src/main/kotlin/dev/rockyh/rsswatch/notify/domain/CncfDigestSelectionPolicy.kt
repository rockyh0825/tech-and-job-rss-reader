package dev.rockyh.rsswatch.notify.domain

import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.shared.contract.RssItem

/**
 * CNCF ダイジェスト候補の記事 1 件。[mentions] は CncfMatchPort(keywords の CNCF 辞書)の検出結果
 * (成熟度の低い順に整列済み)で、言及なしなら空リスト。
 */
data class CncfCandidate(
    val item: RssItem,
    val mentions: List<CncfMention>,
) {
    /** 記事の tier。言及プロジェクトのうち最も低い成熟度(言及なしは null = 最後尾)。 */
    val tier: CncfMaturity? = mentions.minOfOrNull { it.maturity }
}

/**
 * CNCF ダイジェストに載せる記事の選抜。決定的な単一コンパレータで全順序を定める:
 *
 * 1. tier 昇順・null(言及なし)最後尾(sandbox → incubating → graduated → 言及なし。
 *    graduated 前のプロジェクトを早期に掴む、という issue #46 の動機の反映)
 * 2. publishedAt 降順(新着が先。null は最古扱いで tier 内の最後尾)
 * 3. guid 昇順(完全決定性の担保)
 *
 * 並べ替え後に [select] の limit 件で打ち切る(初回有効化時のバックログ氾濫防止)。
 *
 * 同じ全順序を report/application/BuildCncfReportUseCase(Web レポート。cap なし)も持つ。
 * 並び順を変えるときは両方を合わせて変更すること。
 */
class CncfDigestSelectionPolicy {

    private val priorityOrder =
        compareBy<CncfCandidate, CncfMaturity?>(nullsLast(naturalOrder())) { it.tier }
            .thenByDescending { it.item.publishedAt }
            .thenBy { it.item.guid }

    /** [candidates] を優先度の高い順に並べ替え、先頭 [limit] 件を返す。 */
    fun select(candidates: List<CncfCandidate>, limit: Int): List<CncfCandidate> =
        candidates.sortedWith(priorityOrder).take(limit)
}
