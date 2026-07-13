package dev.rockyh.rsswatch.notify

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 興味のある技術の設定。カテゴリ単位([categories])と個別キーワード([keywords])の両方を指定できる。
 *
 * @param categories [dev.rockyh.rsswatch.shared.contract.TechCategory.value] の値(例 `cloud-infra`)
 * @param keywords キーワード辞書の正規化名(大文字小文字は無視。例 `Kotlin`)
 */
@ConfigurationProperties(prefix = "rss-watch.notify.interests")
data class NotifyInterestsProperties(
    val categories: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)
