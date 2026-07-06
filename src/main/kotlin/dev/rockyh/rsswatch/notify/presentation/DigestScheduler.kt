package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.application.BuildDigestUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * デイリーダイジェストを定期起動する入力アダプタ(既定: 毎朝 8:00、cron 設定可能。要件 1.1)。
 * Webhook URL 設定時のみ Bean 登録([ConditionalOnNotifyEnabled])。
 */
@Component
@ConditionalOnNotifyEnabled
class DigestScheduler(
    private val buildDigestUseCase: BuildDigestUseCase,
) {

    @Scheduled(cron = "\${rss-watch.notify.cron:0 0 8 * * *}")
    fun run() {
        buildDigestUseCase.run()
    }
}
