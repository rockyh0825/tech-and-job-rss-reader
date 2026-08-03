package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.ConditionalOnFeedbackEnabled
import dev.rockyh.rsswatch.notify.application.CollectFeedbackUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * フィードバック回収(リアクション・返信)を定期起動する入力アダプタ(既定: 6 時間おき、cron 設定可能)。
 * Bot トークン + いずれかのダイジェスト設定時のみ Bean 登録([ConditionalOnFeedbackEnabled])。
 * ダイジェスト配信(毎朝 8:00)と時刻が重ならない既定にして、単一スレッドのスケジューラを取り合わない。
 */
@Component
@ConditionalOnFeedbackEnabled
class FeedbackScheduler(
    private val collectFeedbackUseCase: CollectFeedbackUseCase,
) {

    @Scheduled(cron = "\${rss-watch.notify.feedback.cron:0 30 */6 * * *}")
    fun run() {
        collectFeedbackUseCase.run()
    }
}
