package dev.rockyh.rsswatch.fetch.infrastructure

import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertContains
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FeedConfigLoaderTest {

    private val loader = FeedConfigLoader()

    @TempDir
    lateinit var tempDir: Path

    private fun tomlFile(content: String): Path {
        val path = tempDir.resolve("feeds.toml")
        path.writeText(content)
        return path
    }

    @Test
    fun feeds_reads_from_the_configured_path_as_feed_config_source() {
        val path =
            tomlFile(
                """
                [[feeds]]
                name = "Zenn"
                url = "https://zenn.dev/feed"
                category = "tech"
                """.trimIndent(),
            )

        val feeds = FeedConfigLoader(feedsPath = path.toString()).feeds()

        assertEquals(
            listOf(FeedDefinition("Zenn", "https://zenn.dev/feed", ItemCategory.TECH)),
            feeds,
        )
    }

    @Test
    fun parses_feeds_with_name_url_and_category() {
        val path =
            tomlFile(
                """
                [[feeds]]
                name = "Zenn"
                url = "https://zenn.dev/feed"
                category = "tech"

                [[feeds]]
                name = "Hacker News Jobs"
                url = "https://hnrss.org/jobs"
                category = "jobs"
                """.trimIndent(),
            )

        val feeds = loader.load(path)

        assertEquals(
            listOf(
                FeedDefinition("Zenn", "https://zenn.dev/feed", ItemCategory.TECH),
                FeedDefinition("Hacker News Jobs", "https://hnrss.org/jobs", ItemCategory.JOBS),
            ),
            feeds,
        )
    }

    @Test
    fun parses_cncf_category_feeds() {
        val path =
            tomlFile(
                """
                [[feeds]]
                name = "CNCF Blog"
                url = "https://www.cncf.io/feed/"
                category = "cncf"
                """.trimIndent(),
            )

        val feeds = loader.load(path)

        assertEquals(
            listOf(FeedDefinition("CNCF Blog", "https://www.cncf.io/feed/", ItemCategory.CNCF)),
            feeds,
        )
    }

    @Test
    fun returns_empty_list_when_no_feeds_are_defined() {
        val path = tomlFile("# コメントだけのファイル")

        val feeds = loader.load(path)

        assertEquals(emptyList(), feeds)
    }

    @Test
    fun throws_when_required_field_url_is_missing() {
        val path =
            tomlFile(
                """
                [[feeds]]
                name = "Zenn"
                category = "tech"
                """.trimIndent(),
            )

        val exception = assertThrows<IllegalArgumentException> { loader.load(path) }

        assertContains(exception.message.orEmpty(), "url", message = "message should name the missing field")
        assertContains(exception.message.orEmpty(), "Zenn", message = "message should identify the feed")
    }

    @Test
    fun throws_when_required_field_name_is_missing() {
        val path =
            tomlFile(
                """
                [[feeds]]
                url = "https://zenn.dev/feed"
                category = "tech"
                """.trimIndent(),
            )

        val exception = assertThrows<IllegalArgumentException> { loader.load(path) }

        assertContains(exception.message.orEmpty(), "name", message = "message should name the missing field")
    }

    @Test
    fun throws_when_category_is_invalid() {
        val path =
            tomlFile(
                """
                [[feeds]]
                name = "Zenn"
                url = "https://zenn.dev/feed"
                category = "news"
                """.trimIndent(),
            )

        val exception = assertThrows<IllegalArgumentException> { loader.load(path) }

        assertContains(exception.message.orEmpty(), "news", message = "message should include the invalid category")
    }

    @Test
    fun parses_the_bundled_feeds_toml() {
        val feeds = loader.load(Path.of("feeds.toml"))

        assertEquals(11, feeds.size)
        assertEquals(5, feeds.count { it.category == ItemCategory.TECH })
        assertEquals(4, feeds.count { it.category == ItemCategory.JOBS })
        assertEquals(2, feeds.count { it.category == ItemCategory.CNCF })
    }
}
