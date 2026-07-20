package dev.rockyh.rsswatch.keywords.domain

import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CncfProjectMatcherTest {

    private val matcher =
        CncfProjectMatcher(
            listOf(
                CncfProject(
                    name = "Kubernetes",
                    maturity = CncfMaturity.GRADUATED,
                    ignoreCaseAliases = listOf("Kubernetes", "k8s"),
                    exactCaseAliases = emptyList(),
                ),
                CncfProject(
                    name = "etcd",
                    maturity = CncfMaturity.GRADUATED,
                    ignoreCaseAliases = listOf("etcd"),
                    exactCaseAliases = emptyList(),
                ),
                CncfProject(
                    name = "Harbor",
                    maturity = CncfMaturity.GRADUATED,
                    ignoreCaseAliases = emptyList(),
                    exactCaseAliases = listOf("Harbor"),
                ),
                CncfProject(
                    name = "OpenTelemetry",
                    maturity = CncfMaturity.INCUBATING,
                    ignoreCaseAliases = listOf("OpenTelemetry", "OTel"),
                    exactCaseAliases = emptyList(),
                ),
                CncfProject(
                    name = "WasmEdge",
                    maturity = CncfMaturity.SANDBOX,
                    ignoreCaseAliases = listOf("WasmEdge"),
                    exactCaseAliases = emptyList(),
                ),
            ),
        )

    @Test
    fun matches_project_name_in_english_prose() {
        val mentions = matcher.match("Scaling Kubernetes clusters in production")

        assertEquals(listOf(CncfMention("Kubernetes", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun matches_ignore_case_alias_regardless_of_case() {
        val mentions = matcher.match("running K8S at home")

        assertEquals(listOf(CncfMention("Kubernetes", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun matches_english_name_adjacent_to_japanese_text() {
        val mentions = matcher.match("Kubernetesで自宅サーバーを運用する")

        assertEquals(listOf(CncfMention("Kubernetes", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun does_not_match_exact_case_alias_in_lowercase() {
        val mentions = matcher.match("ships arrived at the harbor yesterday")

        assertEquals(emptyList(), mentions)
    }

    @Test
    fun matches_exact_case_alias_with_original_casing() {
        val mentions = matcher.match("Harbor v2.12 release notes")

        assertEquals(listOf(CncfMention("Harbor", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun does_not_match_alias_inside_a_longer_word() {
        val mentions = matcher.match("the prefetcher improves performance")

        assertEquals(emptyList(), mentions)
    }

    @Test
    fun returns_mentions_sorted_by_maturity_sandbox_first_then_name() {
        val mentions = matcher.match("OpenTelemetry meets WasmEdge and Kubernetes with etcd")

        assertEquals(
            listOf(
                CncfMention("WasmEdge", CncfMaturity.SANDBOX),
                CncfMention("OpenTelemetry", CncfMaturity.INCUBATING),
                CncfMention("etcd", CncfMaturity.GRADUATED),
                CncfMention("Kubernetes", CncfMaturity.GRADUATED),
            ),
            mentions,
        )
    }

    @Test
    fun returns_a_project_once_even_when_mentioned_by_multiple_aliases() {
        val mentions = matcher.match("Kubernetes (k8s) is everywhere")

        assertEquals(listOf(CncfMention("Kubernetes", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun returns_empty_for_blank_text() {
        assertEquals(emptyList(), matcher.match("   "))
    }
}
