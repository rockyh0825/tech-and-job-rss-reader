package dev.rockyh.rsswatch.notify

import org.springframework.boot.autoconfigure.condition.AllNestedConditions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase

/**
 * フィードバック回収(リアクション・返信の収集)の有効化条件。Bot トークンが設定されていて、かつ
 * どちらかのダイジェスト([ConditionalOnAnyNotifyEnabled])が有効なときだけ Bean を登録する。
 *
 * ダイジェスト側の条件も要求するのは、投稿が無ければ回収対象のメッセージが記録されないため
 * (トークンだけ設定しても収集するものが無い)。トークン未設定なら回収一式は Bean 未登録のままで、
 * 投稿側(message ID の記録まで)は影響を受けない。
 *
 * `bot-token` に application.yml のデフォルト値は置かないこと(未設定=未定義で無効にするため。
 * Webhook URL と同じ規則)。トークンは環境変数 RSS_WATCH_NOTIFY_FEEDBACK_BOT_TOKEN で渡す。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(FeedbackEnabledCondition::class)
annotation class ConditionalOnFeedbackEnabled

class FeedbackEnabledCondition : AllNestedConditions(ConfigurationPhase.REGISTER_BEAN) {

    @ConditionalOnProperty(name = ["rss-watch.notify.feedback.bot-token"])
    class BotTokenConfigured

    @Conditional(AnyNotifyEnabledCondition::class)
    class AnyDigestConfigured
}
