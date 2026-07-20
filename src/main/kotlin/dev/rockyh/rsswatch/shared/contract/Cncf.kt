package dev.rockyh.rsswatch.shared.contract

/**
 * CNCF プロジェクトの成熟度(https://www.cncf.io/projects/ の Maturity Levels)。
 * 宣言順 = 表示・ダイジェストでの優先順(成熟度が低いほど「早期に掴む」価値が高いので先頭)。
 *
 * feature 横断の語彙(ItemCategory と同格): keywords が照合結果として返し、
 * notify(ダイジェスト)と report(Web レポート)が表示に使う。
 */
enum class CncfMaturity(val label: String, val emoji: String) {
    SANDBOX("Sandbox", "🌱"),
    INCUBATING("Incubating", "🧪"),
    GRADUATED("Graduated", "🎓"),
}

/** テキスト中で言及された CNCF プロジェクト 1 件。 */
data class CncfMention(
    val projectName: String,
    val maturity: CncfMaturity,
)
