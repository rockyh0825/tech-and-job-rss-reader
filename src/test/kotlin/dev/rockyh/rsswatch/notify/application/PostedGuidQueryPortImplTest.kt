package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

class PostedGuidQueryPortImplTest {

    private class FakePostedGuidStore(private val posted: Set<String>) : PostedGuidStore {
        val receivedSince = mutableListOf<Instant>()

        override fun postedGuids(since: Instant): Set<String> {
            receivedSince += since
            return posted
        }

        override fun markPosted(guids: List<String>) = error("not used by the query port")
    }

    /** notify 無効時は PostedGuidStore の Bean が存在しない状況を再現する。 */
    private class FakeStoreProvider(private val store: PostedGuidStore?) : ObjectProvider<PostedGuidStore> {
        override fun getObject(): PostedGuidStore = store ?: error("no store available")

        override fun getIfAvailable(): PostedGuidStore? = store
    }

    @Test
    fun returns_only_given_guids_recorded_as_posted() {
        val store = FakePostedGuidStore(setOf("posted-1", "posted-2", "posted-elsewhere"))
        val port = PostedGuidQueryPortImpl(FakeStoreProvider(store))

        val posted = port.postedIn(setOf("posted-1", "posted-2", "fresh"))

        assertEquals(setOf("posted-1", "posted-2"), posted)
        assertEquals(listOf(Instant.EPOCH), store.receivedSince)
    }

    @Test
    fun returns_empty_set_when_nothing_was_recorded() {
        val port = PostedGuidQueryPortImpl(FakeStoreProvider(FakePostedGuidStore(emptySet())))

        assertEquals(emptySet(), port.postedIn(setOf("fresh")))
    }

    @Test
    fun returns_empty_set_when_notify_is_disabled_and_store_bean_is_absent() {
        val port = PostedGuidQueryPortImpl(FakeStoreProvider(null))

        assertEquals(emptySet(), port.postedIn(setOf("posted-1")))
    }
}
