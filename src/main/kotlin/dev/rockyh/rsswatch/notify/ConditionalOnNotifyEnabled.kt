package dev.rockyh.rsswatch.notify

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * notify feature の有効化条件。Discord Webhook URL が設定されているときだけ Bean を登録する。
 * URL 未設定なら notify の Bean は一切登録されず、アプリは通常起動する(要件 4.3)。
 *
 * `discord-webhook-url` に application.yml のデフォルト値は置かないこと(未設定=未定義で無効にするため)。
 * API キー未設定は「無効化」ではなく実行時フォールバック(要件 2.2)なのでトグル条件に含めない。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["rss-watch.notify.discord-webhook-url"])
annotation class ConditionalOnNotifyEnabled
