package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CncfMatchPortImplTest {

    private val port = CncfMatchPortImpl()

    @Test
    fun delegates_matching_to_the_bundled_cncf_dictionary() {
        val mentions = port.match("Kubernetesで始める運用入門")

        assertEquals(listOf(CncfMention("Kubernetes", CncfMaturity.GRADUATED)), mentions)
    }

    @Test
    fun returns_mentions_sorted_by_maturity_ascending() {
        val mentions = port.match("Kubernetes meets WasmEdge and Knative")

        assertEquals(
            listOf(
                CncfMention("WasmEdge", CncfMaturity.SANDBOX),
                CncfMention("Knative", CncfMaturity.INCUBATING),
                CncfMention("Kubernetes", CncfMaturity.GRADUATED),
            ),
            mentions,
        )
    }

    @Test
    fun returns_empty_list_for_text_without_cncf_mentions() {
        assertEquals(emptyList(), port.match("今週の求人まとめ"))
    }
}
