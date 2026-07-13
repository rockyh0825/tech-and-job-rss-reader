package dev.rockyh.rsswatch.notify.domain

import java.time.Instant

/**
 * ダイジェスト候補の技術 1 件。
 *
 * @property keyword 技術キーワードの正規化名
 * @property mentionCount 期間内の求人での言及数(求人に出ていない興味技術は 0)
 * @property interested 興味設定([NotifyInterests])に含まれるか
 * @property lastFeaturedAt 最後にダイジェストで紹介した日時(未紹介なら null)
 */
data class TechCandidate(
    val keyword: String,
    val mentionCount: Int,
    val interested: Boolean,
    val lastFeaturedAt: Instant?,
)

/**
 * ダイジェストに載せる技術の優先順位付け。決定的な単一コンパレータで全順序を定める:
 *
 * 1. interested 降順(興味技術が先)
 * 2. lastFeaturedAt 昇順・null(未紹介)最優先(ローテーション:最近紹介した技術を後回し)
 * 3. mentionCount 降順(求人言及数のタイブレーク)
 * 4. keyword 昇順(完全決定性の担保)
 *
 * ローテーションは興味・非興味の両グループに一様に適用する(興味技術同士でも巡回させる)。
 * また期限なし(クールダウン日数のような閾値は持たない):未紹介 → 紹介が古い順に全技術を
 * 巡回するため、求人言及数の多い人気技術も一巡するまでは再登場しない。これは「同じ技術ばかり
 * 連日出る」ことを避けるための意図的な仕様。
 */
class DigestSelectionPolicy {

    private val priorityOrder =
        compareByDescending<TechCandidate> { it.interested }
            .thenBy(nullsFirst(naturalOrder())) { it.lastFeaturedAt }
            .thenByDescending { it.mentionCount }
            .thenBy { it.keyword }

    /** [candidates] を優先度の高い順に並べ替えて返す。 */
    fun prioritize(candidates: List<TechCandidate>): List<TechCandidate> =
        candidates.sortedWith(priorityOrder)
}
