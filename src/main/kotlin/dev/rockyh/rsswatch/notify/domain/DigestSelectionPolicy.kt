package dev.rockyh.rsswatch.notify.domain

import java.time.Duration
import java.time.Instant

/**
 * ダイジェスト候補の技術 1 件。
 *
 * @property keyword 技術キーワードの正規化名
 * @property mentionCount 期間内の記事での言及数(ランキング外の興味技術は 0)
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
 * 2. クールダウン中(直近 [cooldownDays] 日以内に紹介済み)の技術を後回し
 * 3. mentionCount 降順(記事言及数の多い順)
 * 4. keyword 昇順(完全決定性の担保)
 *
 * ローテーションは興味・非興味の両グループに一様に適用する(興味技術同士でも巡回させる)。
 * クールダウンは「同じ技術ばかり連日出る」ことを避けるためのもので、明けた技術は未紹介の技術と
 * 同格になり言及数で競う。かつては期限なし(未紹介 → 紹介が古い順に全技術を巡回)だったが、
 * 紹介済みの注目技術がロングテールの一巡を待つ間にマイナー技術ばかりが浮上したため、
 * クールダウン方式に変更した(issue #70 Step 1)。
 *
 * 境界はちょうど [cooldownDays] 日前ならクールダウン明け扱い(排他的)。
 */
class DigestSelectionPolicy(private val cooldownDays: Int) {

    init {
        require(cooldownDays > 0) { "cooldownDays must be positive: $cooldownDays" }
    }

    /** [candidates] を [now] 時点の優先度の高い順に並べ替えて返す。 */
    fun prioritize(candidates: List<TechCandidate>, now: Instant): List<TechCandidate> {
        val cooldownStart = now.minus(Duration.ofDays(cooldownDays.toLong()))
        val priorityOrder =
            compareByDescending<TechCandidate> { it.interested }
                .thenBy { candidate -> candidate.lastFeaturedAt?.isAfter(cooldownStart) ?: false }
                .thenByDescending { it.mentionCount }
                .thenBy { it.keyword }
        return candidates.sortedWith(priorityOrder)
    }
}
