package dev.rockyh.rsswatch.notify

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * CNCF ダイジェスト(issue #46)の有効化条件。CNCF 用の Discord Webhook URL が設定されているときだけ
 * Bean を登録する。既存ダイジェスト([ConditionalOnNotifyEnabled])とは独立にオン/オフできる。
 *
 * `cncf.discord-webhook-url` に application.yml のデフォルト値は置かないこと(未設定=未定義で無効にするため)。
 * 両ダイジェスト共有の部品(要約・サムネイル・投稿済み管理)は [ConditionalOnAnyNotifyEnabled] を使う。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["rss-watch.notify.cncf.discord-webhook-url"])
annotation class ConditionalOnCncfNotifyEnabled
