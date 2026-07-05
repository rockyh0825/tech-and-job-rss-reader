package dev.rockyh.rsswatch.live.presentation

import dev.rockyh.rsswatch.live.application.SseBroadcaster
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** リアルタイム新着の SSE エンドポイント(要件 4.1)。 */
@RestController
class SseController(private val sseBroadcaster: SseBroadcaster) {

    @GetMapping("/api/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = sseBroadcaster.register(SseEmitter(NO_TIMEOUT))

    companion object {
        /** SSE は長寿命接続のためサーバー側ではタイムアウトさせない(切断はクライアント任せ)。 */
        private const val NO_TIMEOUT = 0L
    }
}
