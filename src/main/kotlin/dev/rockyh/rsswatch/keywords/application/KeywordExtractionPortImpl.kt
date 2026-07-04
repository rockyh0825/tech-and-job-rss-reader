package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.capabilities.KeywordExtractionPort
import dev.rockyh.rsswatch.keywords.domain.KeywordExtractor
import org.springframework.stereotype.Component

/** [KeywordExtractionPort] の実装。domain の [KeywordExtractor] に委譲する。 */
@Component
class KeywordExtractionPortImpl(
    private val extractor: KeywordExtractor = KeywordExtractor(),
) : KeywordExtractionPort {

    override fun extract(text: String): Set<String> = extractor.extract(text)
}
