package dev.rockyh.rsswatch.keywords.domain

/**
 * テキストから技術キーワード(正規化名)の集合を抽出する。
 *
 * 日本語文中の英語キーワード(例: `Pythonで`)では `\b` が機能しないため、
 * 独自境界 `(?<![A-Za-z0-9])...(?![A-Za-z0-9+#])` で照合する。
 * 後方の `+`/`#` の除外により、`C` が `C++`/`C#` の一部に誤マッチしない。
 */
class KeywordExtractor(entries: List<KeywordEntry> = Keywords.entries) {

    private val matchers: List<Matcher> =
        entries.map { entry ->
            Matcher(
                normalizedName = entry.normalizedName,
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

    fun extract(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        return matchers
            .filter { matcher -> matcher.regexes.any { it.containsMatchIn(text) } }
            .map { it.normalizedName }
            .toSet()
    }

    private fun boundedPattern(aliases: List<String>): String {
        val alternation = aliases.joinToString("|") { Regex.escape(it) }
        return "(?<![A-Za-z0-9])(?:$alternation)(?![A-Za-z0-9+#])"
    }

    private data class Matcher(
        val normalizedName: String,
        val regexes: List<Regex>,
    )
}
