package dev.rockyh.rsswatch.live.application

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class SseBroadcasterTest {

    /** send された SSE イベントのデータを記録する emitter。 */
    private open class RecordingEmitter : SseEmitter(0L) {
        val sentData = mutableListOf<String>()

        override fun send(builder: SseEventBuilder) {
            sentData +=
                builder
                    .build()
                    .map { it.data }
                    .filterIsInstance<String>()
                    .joinToString("")
        }
    }

    private class FailingEmitter : SseEmitter(0L) {
        override fun send(builder: SseEventBuilder) {
            throw IOException("client disconnected")
        }
    }

    @Test
    fun broadcast_delivers_event_to_all_registered_clients() {
        val broadcaster = SseBroadcaster()
        val first = RecordingEmitter()
        val second = RecordingEmitter()
        broadcaster.register(first)
        broadcaster.register(second)

        broadcaster.broadcast("""{"guid":"a"}""")

        assertTrue(first.sentData.single().contains("""{"guid":"a"}"""))
        assertTrue(second.sentData.single().contains("""{"guid":"a"}"""))
    }

    @Test
    fun register_increases_client_count() {
        val broadcaster = SseBroadcaster()

        broadcaster.register(RecordingEmitter())

        assertEquals(1, broadcaster.clientCount())
    }

    @Test
    fun broadcast_removes_clients_that_fail_to_receive_and_keeps_delivering_to_others() {
        val broadcaster = SseBroadcaster()
        val failing = FailingEmitter()
        val healthy = RecordingEmitter()
        broadcaster.register(failing)
        broadcaster.register(healthy)

        broadcaster.broadcast("first")
        broadcaster.broadcast("second")

        assertEquals(1, broadcaster.clientCount())
        assertEquals(2, healthy.sentData.size)
    }

    @Test
    fun broadcast_to_no_clients_does_not_fail() {
        val broadcaster = SseBroadcaster()

        broadcaster.broadcast("event")

        assertEquals(0, broadcaster.clientCount())
    }
}
