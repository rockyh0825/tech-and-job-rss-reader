package dev.rockyh.rsswatch.live.application

import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 接続中の SSE クライアントを管理し、新着 item(JSON)を全クライアントへ配信する。
 * 切断・タイムアウト・送信失敗したクライアントはリストから除去する(要件 4.3)。
 */
@Service
class SseBroadcaster {

    private val log = LoggerFactory.getLogger(javaClass)

    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    /** クライアントを配信対象に登録し、切断時に自動で除去されるよう結線する。 */
    fun register(emitter: SseEmitter): SseEmitter {
        emitters += emitter
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        // 初期コメントを送ってレスポンスヘッダを即時 flush する
        // (これがないと最初の item まで EventSource の open が発火しない)
        try {
            emitter.send(SseEmitter.event().comment("connected"))
        } catch (e: Exception) {
            log.debug("removing SSE client that failed the initial send", e)
            emitters.remove(emitter)
        }
        return emitter
    }

    /** 全クライアントへ item イベントを配信する。送信に失敗したクライアントは除去して続行する。 */
    fun broadcast(json: String) {
        for (emitter in emitters) {
            try {
                emitter.send(SseEmitter.event().name("item").data(json))
            } catch (e: Exception) {
                log.debug("removing disconnected SSE client", e)
                emitters.remove(emitter)
            }
        }
    }

    fun clientCount(): Int = emitters.size
}
