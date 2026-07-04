package dev.rockyh.rsswatch.fetch.infrastructure

import dev.rockyh.rsswatch.fetch.domain.FeedCategory
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RomeFeedParserTest {

    private val parser = RomeFeedParser()

    @TempDir
    lateinit var tempDir: Path

    private fun feedFor(xml: String): FeedDefinition {
        val path = tempDir.resolve("feed.xml")
        path.writeText(xml)
        return FeedDefinition("テストフィード", path.toUri().toString(), FeedCategory.TECH)
    }

    @Test
    fun parses_rss2_entries_with_guid_title_link_summary_and_published_at() {
        val feed =
            feedFor(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Example Feed</title>
                    <link>https://example.com</link>
                    <description>example</description>
                    <item>
                      <guid>tag:example.com,2026:1</guid>
                      <title>Kotlinの記事</title>
                      <link>https://example.com/articles/1</link>
                      <description>Spring Bootの解説</description>
                      <pubDate>Sun, 05 Jul 2026 09:00:00 GMT</pubDate>
                    </item>
                  </channel>
                </rss>
                """.trimIndent(),
            )

        val entries = parser.parse(feed)

        assertEquals(
            listOf(
                dev.rockyh.rsswatch.fetch.domain.ParsedEntry(
                    guid = "tag:example.com,2026:1",
                    title = "Kotlinの記事",
                    url = "https://example.com/articles/1",
                    summary = "Spring Bootの解説",
                    publishedAt = Instant.parse("2026-07-05T09:00:00Z"),
                ),
            ),
            entries,
        )
    }

    @Test
    fun uses_link_as_guid_when_guid_is_missing() {
        val feed =
            feedFor(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Example Feed</title>
                    <link>https://example.com</link>
                    <description>example</description>
                    <item>
                      <title>guidなし記事</title>
                      <link>https://example.com/articles/2</link>
                    </item>
                  </channel>
                </rss>
                """.trimIndent(),
            )

        val entries = parser.parse(feed)

        assertEquals("https://example.com/articles/2", entries.single().guid)
    }

    @Test
    fun maps_missing_summary_and_published_at_to_empty_and_null() {
        val feed =
            feedFor(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Example Feed</title>
                    <link>https://example.com</link>
                    <description>example</description>
                    <item>
                      <title>本文なし記事</title>
                      <link>https://example.com/articles/3</link>
                    </item>
                  </channel>
                </rss>
                """.trimIndent(),
            )

        val entry = parser.parse(feed).single()

        assertEquals("", entry.summary)
        assertEquals(null, entry.publishedAt)
    }

    @Test
    fun parses_atom_feeds_as_well() {
        val feed =
            feedFor(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom Feed</title>
                  <id>tag:example.com,2026:feed</id>
                  <updated>2026-07-05T09:00:00Z</updated>
                  <entry>
                    <id>tag:example.com,2026:atom-1</id>
                    <title>Atomの記事</title>
                    <link href="https://example.com/atom/1"/>
                    <summary>k8sの話</summary>
                    <updated>2026-07-05T09:00:00Z</updated>
                  </entry>
                </feed>
                """.trimIndent(),
            )

        val entry = parser.parse(feed).single()

        assertEquals("tag:example.com,2026:atom-1", entry.guid)
        assertEquals("Atomの記事", entry.title)
        assertEquals("https://example.com/atom/1", entry.url)
        assertEquals("k8sの話", entry.summary)
    }

    @Test
    fun throws_when_feed_is_not_valid_xml() {
        val feed = feedFor("これはXMLではない")

        assertThrows<Exception> { parser.parse(feed) }
    }
}
