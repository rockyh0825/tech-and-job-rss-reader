package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.ConditionalOnCncfNotifyEnabled
import dev.rockyh.rsswatch.notify.application.BuildCncfDigestUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * CNCF ダイジェストを定期起動する入力アダプタ(既定: 毎朝 8:10、cron 設定可能)。
 * 既存ダイジェスト(8:00)と時刻をずらし、スケジューリングスレッドの取り合いを避ける。
 * CNCF 用 Webhook URL 設定時のみ Bean 登録([ConditionalOnCncfNotifyEnabled])。
 */
@Component
@ConditionalOnCncfNotifyEnabled
class CncfDigestScheduler(
    private val buildCncfDigestUseCase: BuildCncfDigestUseCase,
) {

    @Scheduled(cron = "\${rss-watch.notify.cncf.cron:0 10 8 * * *}")
    fun run() {
        buildCncfDigestUseCase.run()
    }
}
