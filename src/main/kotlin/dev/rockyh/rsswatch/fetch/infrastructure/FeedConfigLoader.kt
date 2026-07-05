package dev.rockyh.rsswatch.fetch.infrastructure

import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.rockyh.rsswatch.fetch.domain.FeedCategory
import dev.rockyh.rsswatch.fetch.domain.FeedConfigSource
import dev.rockyh.rsswatch.fetch.domain.FeedDefinition
import java.nio.file.Path
import kotlin.io.path.readText
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** feeds.toml を読み込んで [FeedDefinition] のリストに変換する [FeedConfigSource] 実装。 */
@Component
class FeedConfigLoader(
    @Value("\${rss-watch.feeds-path:feeds.toml}") private val feedsPath: String = "feeds.toml",
) : FeedConfigSource {

    private val tomlMapper = TomlMapper.builder().addModule(kotlinModule()).build()

    override fun feeds(): List<FeedDefinition> = load(Path.of(feedsPath))

    fun load(path: Path): List<FeedDefinition> {
        val file: FeedsFile = tomlMapper.readValue(path.readText())
        return file.feeds.mapIndexed { index, entry -> entry.toDefinition(index) }
    }

    private data class FeedsFile(
        val feeds: List<FeedEntry> = emptyList(),
    )

    private data class FeedEntry(
        val name: String? = null,
        val url: String? = null,
        val category: String? = null,
    ) {
        fun toDefinition(index: Int): FeedDefinition {
            val feedLabel = name ?: "feeds[$index]"
            return FeedDefinition(
                name = requireField(name, "name", feedLabel),
                url = requireField(url, "url", feedLabel),
                category = FeedCategory.from(requireField(category, "category", feedLabel)),
            )
        }

        private fun requireField(value: String?, field: String, feedLabel: String): String =
            requireNotNull(value) { "missing required field \"$field\" in feed \"$feedLabel\"" }
    }
}
