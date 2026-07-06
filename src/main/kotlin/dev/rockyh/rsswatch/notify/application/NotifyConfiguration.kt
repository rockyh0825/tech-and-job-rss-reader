package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestSelectionPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * notify feature の DI 配線。純 Kotlin の [DigestSelectionPolicy] に設定値(人気フィード)を注入して
 * Bean 化する。feature 全体は [ConditionalOnNotifyEnabled](Webhook URL 設定時のみ有効)でトグルする。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotifyEnabled
class NotifyConfiguration {

    @Bean
    fun digestSelectionPolicy(
        @Value("\${rss-watch.notify.popular-feeds:$DEFAULT_POPULAR_FEEDS}") popularFeeds: List<String>,
    ): DigestSelectionPolicy = DigestSelectionPolicy(popularFeeds.toSet())

    companion object {
        private const val DEFAULT_POPULAR_FEEDS =
            "はてなブックマーク テクノロジー,Qiita 人気記事,Hacker News"
    }
}
