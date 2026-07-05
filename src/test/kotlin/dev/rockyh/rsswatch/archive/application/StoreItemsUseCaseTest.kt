package dev.rockyh.rsswatch.archive.application

import dev.rockyh.rsswatch.archive.domain.ItemStore
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class StoreItemsUseCaseTest {

    private class RecordingItemStore : ItemStore {
        val received = mutableListOf<List<RssItem>>()

        override fun insertIgnore(items: List<RssItem>): Int {
            received += items
            return items.size
        }
    }

    private fun rssItem(guid: String): RssItem =
        RssItem(
            guid = guid,
            feedName = "feed",
            category = "tech",
            title = "title",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = null,
            fetchedAt = Instant.now(),
            keywords = emptyList(),
        )

    @Test
    fun stores_items_through_item_store_and_returns_inserted_count() {
        val store = RecordingItemStore()
        val useCase = StoreItemsUseCase(store)
        val items = listOf(rssItem("a"), rssItem("b"))

        val inserted = useCase.store(items)

        assertEquals(2, inserted)
        assertEquals(listOf(items), store.received)
    }

    @Test
    fun does_not_touch_item_store_when_batch_is_empty() {
        val store = RecordingItemStore()
        val useCase = StoreItemsUseCase(store)

        val inserted = useCase.store(emptyList())

        assertEquals(0, inserted)
        assertEquals(emptyList(), store.received)
    }
}
