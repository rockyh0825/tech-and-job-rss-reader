package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.capabilities.KeywordExtractionPort
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class KeywordExtractionPortImplTest {

    private val port: KeywordExtractionPort = KeywordExtractionPortImpl()

    @Test
    fun extracts_normalized_keywords_through_the_port() {
        val keywords = port.extract("k8sとgolangでマイクロサービスを作る")

        assertEquals(setOf("Kubernetes", "Go"), keywords)
    }

    @Test
    fun returns_empty_set_for_text_without_keywords() {
        assertEquals(emptySet(), port.extract("技術の話ではない日記"))
    }
}
