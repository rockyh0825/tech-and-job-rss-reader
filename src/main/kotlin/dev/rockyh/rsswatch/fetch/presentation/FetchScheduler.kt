package dev.rockyh.rsswatch.fetch.presentation

import dev.rockyh.rsswatch.fetch.application.FetchFeedsUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 設定間隔で全フィードの巡回を起動する入力アダプタ(要件 1.1)。 */
@Component
class FetchScheduler(
    private val fetchFeedsUseCase: FetchFeedsUseCase,
) {

    @Scheduled(
        initialDelayString = "\${rss-watch.fetch.initial-delay-ms:10000}",
        fixedDelayString = "\${rss-watch.fetch.interval-ms:900000}",
    )
    fun run() {
        fetchFeedsUseCase.fetchAll()
    }
}
