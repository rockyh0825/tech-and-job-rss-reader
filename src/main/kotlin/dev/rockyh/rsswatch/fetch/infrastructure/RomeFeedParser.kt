package dev.rockyh.rsswatch.fetch.infrastructure

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import dev.rockyh.rsswatch.fetch.domain.FeedParser
import dev.rockyh.rsswatch.fetch.domain.ParsedEntry
import java.net.URI
import org.springframework.stereotype.Component

/** Rome で RSS/Atom フィードを取得・パースする [FeedParser] 実装。 */
@Component
class RomeFeedParser : FeedParser {

    override fun parse(feed: FeedDefinition): List<ParsedEntry> {
        val syndFeed = XmlReader(URI(feed.url).toURL()).use { SyndFeedInput().build(it) }
        return syndFeed.entries.map { entry ->
            ParsedEntry(
                guid = entry.uri ?: entry.link.orEmpty(),
                title = entry.title.orEmpty(),
                url = entry.link.orEmpty(),
                summary = entry.description?.value.orEmpty(),
                publishedAt = entry.publishedDate?.toInstant(),
            )
        }
    }
}
