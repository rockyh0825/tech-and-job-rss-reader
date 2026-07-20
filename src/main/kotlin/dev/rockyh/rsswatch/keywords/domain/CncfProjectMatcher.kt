package dev.rockyh.rsswatch.keywords.domain

import dev.rockyh.rsswatch.shared.contract.CncfMention

/**
 * テキストから CNCF プロジェクトの言及を検出する。
 *
 * 照合は同 feature の [KeywordExtractor] と同じ独自境界イディオム:
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
