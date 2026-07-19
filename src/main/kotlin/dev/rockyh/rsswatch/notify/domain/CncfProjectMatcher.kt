package dev.rockyh.rsswatch.notify.domain

/** テキスト中で言及された CNCF プロジェクト 1 件。 */
data class CncfMention(
    val projectName: String,
    val maturity: CncfMaturity,
)

/**
 * テキストから CNCF プロジェクトの言及を検出する。
 *
 * 照合は keywords/domain/KeywordExtractor と同じ独自境界イディオムの意図的な小複製
 * (Konsist の feature 分離ルールで keywords.domain を import できないため):
 * 日本語文中の英語名(例: `Kubernetesで`)では `\b` が機能しないため
 * `(?<![A-Za-z0-9])...(?![A-Za-z0-9+#])` で照合する。
 *
 * 結果は成熟度の低い順(sandbox → incubating → graduated)、同成熟度内は名前順で返す。
 */
class CncfProjectMatcher(entries: List<CncfProject> = CncfProjects.entries) {

    private val matchers: List<Matcher> =
        entries.map { entry ->
            Matcher(
                mention = CncfMention(entry.name, entry.maturity),
                regexes =
                    buildList {
                        if (entry.ignoreCaseAliases.isNotEmpty()) {
                            add(boundedPattern(entry.ignoreCaseAliases).toRegex(RegexOption.IGNORE_CASE))
                        }
                        if (entry.exactCaseAliases.isNotEmpty()) {
                            add(boundedPattern(entry.exactCaseAliases).toRegex())
                        }
                    },
            )
        }

    fun match(text: String): List<CncfMention> {
        if (text.isBlank()) return emptyList()
        return matchers
            .filter { matcher -> matcher.regexes.any { it.containsMatchIn(text) } }
            .map { it.mention }
            .sortedWith(compareBy({ it.maturity }, { it.projectName.lowercase() }))
    }

    private fun boundedPattern(aliases: List<String>): String {
        val alternation = aliases.joinToString("|") { Regex.escape(it) }
        return "(?<![A-Za-z0-9])(?:$alternation)(?![A-Za-z0-9+#])"
    }

    private data class Matcher(
        val mention: CncfMention,
        val regexes: List<Regex>,
    )
}
