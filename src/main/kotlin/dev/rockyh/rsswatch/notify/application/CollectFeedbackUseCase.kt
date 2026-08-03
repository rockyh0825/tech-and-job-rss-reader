package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.notify.ConditionalOnFeedbackEnabled
import dev.rockyh.rsswatch.notify.domain.DigestMessageStore
import dev.rockyh.rsswatch.notify.domain.DiscordFeedbackSource
import dev.rockyh.rsswatch.notify.domain.DiscordMessageRef
import dev.rockyh.rsswatch.notify.domain.FeedbackStore
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * ダイジェスト投稿へのフィードバック(リアクション・返信)の定期回収(issue #72)。
 *
 * 直近 [lookbackDays] 日に投稿したメッセージ([DigestMessageStore])を対象に、
 * - リアクションはメッセージごとに現在値を取得してスナップショット置き換え
 *   ([FeedbackStore.replaceReactions]。取り消しも自然に反映される)
 * - 返信はチャンネルごとに 1 回のまとめ照会([DiscordFeedbackSource.repliesTo])で upsert
 *
 * verdict(GOOD/BAD 等)への解釈はせず生データのまま貯める(解釈は #70 Step 4 で決める)。
 * 個々の取得失敗は warn に留めて他のメッセージの回収を続ける。定期実行なので、失敗ぶんは
 * 次回の巡回で自然に追いつく(スナップショット方式のため取りこぼしが蓄積しない)。
 */
@Service
@ConditionalOnFeedbackEnabled
class CollectFeedbackUseCase(
    private val messageStore: DigestMessageStore,
    private val feedbackSource: DiscordFeedbackSource,
    private val feedbackStore: FeedbackStore,
    @Value("\${rss-watch.notify.feedback.lookback-days:7}") private val lookbackDays: Int,
    private val clock: Clock = Clock.systemUTC(),
) {

    init {
        require(lookbackDays > 0) { "rss-watch.notify.feedback.lookback-days must be positive: $lookbackDays" }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        val tracked = messageStore.messagesSince(clock.instant().minus(Duration.ofDays(lookbackDays.toLong())))
        if (tracked.isEmpty()) {
            log.info("no tracked digest messages within {} days; nothing to collect", lookbackDays)
            return
        }

        for ((channelId, messages) in tracked.groupBy { it.channelId }) {
            collectReplies(channelId, messages)
            messages.forEach(::collectReactions)
        }
        log.info("collected feedback for {} digest messages", tracked.size)
    }

    private fun collectReplies(channelId: String, messages: List<DiscordMessageRef>) {
        runCatching { feedbackSource.repliesTo(channelId, messages.map { it.messageId }.toSet()) }
            .onSuccess { feedbackStore.recordReplies(it) }
            .onFailure { log.warn("failed to collect replies for channel {}; will retry next schedule", channelId, it) }
    }

    private fun collectReactions(message: DiscordMessageRef) {
        runCatching { feedbackSource.reactions(message.channelId, message.messageId) }
            .onSuccess { reactions ->
                // null = メッセージが削除済み。「0 件」と違い取得できていないので、前回のスナップショットは残す
                if (reactions != null) feedbackStore.replaceReactions(message.messageId, reactions)
            }
            .onFailure {
                log.warn("failed to collect reactions for message {}; will retry next schedule", message.messageId, it)
            }
    }
}
