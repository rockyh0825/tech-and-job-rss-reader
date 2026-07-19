package dev.rockyh.rsswatch.notify

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * 既存ダイジェスト(求人技術 × 関連記事)の有効化条件。Discord Webhook URL が設定されているときだけ
 * Bean を登録する。URL 未設定なら該当 Bean は登録されず、アプリは通常起動する(要件 4.3)。
 *
 * `discord-webhook-url` に application.yml のデフォルト値は置かないこと(未設定=未定義で無効にするため)。
 * API キー未設定は「無効化」ではなく実行時フォールバック(要件 2.2)なのでトグル条件に含めない。
 *
 * CNCF ダイジェストのトグルは [ConditionalOnCncfNotifyEnabled]、両ダイジェスト共有の部品
 * (要約・サムネイル・投稿済み管理)は [ConditionalOnAnyNotifyEnabled] を使う。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["rss-watch.notify.discord-webhook-url"])
annotation class ConditionalOnNotifyEnabled
