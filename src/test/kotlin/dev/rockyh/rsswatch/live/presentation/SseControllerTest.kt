package dev.rockyh.rsswatch.live.presentation

import dev.rockyh.rsswatch.live.application.SseBroadcaster
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SseControllerTest {

    @Test
    fun stream_registers_a_new_emitter_to_the_broadcaster() {
        val broadcaster = SseBroadcaster()
        val controller = SseController(broadcaster)

        controller.stream()

        assertEquals(1, broadcaster.clientCount())
    }
}
