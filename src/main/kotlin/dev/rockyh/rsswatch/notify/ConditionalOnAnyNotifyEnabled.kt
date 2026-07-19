package dev.rockyh.rsswatch.notify

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase

/**
 * 両ダイジェスト共有の部品(要約・サムネイル・投稿済み管理)の有効化条件。
 * 既存ダイジェスト([ConditionalOnNotifyEnabled])と CNCF ダイジェスト([ConditionalOnCncfNotifyEnabled])の
 * **どちらか一方でも**有効なら Bean を登録する。
 *
 * 共有部品を [ConditionalOnNotifyEnabled](既存側の URL のみ)でガードすると、CNCF 側だけ設定した
 * デプロイで CNCF の UseCase が依存を解決できず起動に失敗する。ここが 2 チャンネル化の要。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(AnyNotifyEnabledCondition::class)
annotation class ConditionalOnAnyNotifyEnabled

class AnyNotifyEnabledCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {

    @ConditionalOnProperty(name = ["rss-watch.notify.discord-webhook-url"])
    class TechWebhookConfigured

    @ConditionalOnProperty(name = ["rss-watch.notify.cncf.discord-webhook-url"])
    class CncfWebhookConfigured
}
