package dev.rockyh.rsswatch.notify

import dev.rockyh.rsswatch.capabilities.KeywordCatalogPort
import dev.rockyh.rsswatch.notify.domain.NotifyInterests
import dev.rockyh.rsswatch.shared.contract.TechCategory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 興味設定([NotifyInterestsProperties])を解決済みの [NotifyInterests] に変換する配線。
 *
 * 未知のカテゴリ名・キーワード名は起動失敗にする(fail-fast)。notify は Webhook URL 設定時のみ
 * 有効になる明示的オプトイン機能であり、typo を警告で握りつぶすと興味設定が静かに効かなくなるため。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotifyEnabled
@EnableConfigurationProperties(NotifyInterestsProperties::class)
class NotifyInterestsConfig {

    @Bean
    fun notifyInterests(
        properties: NotifyInterestsProperties,
        keywordCatalog: KeywordCatalogPort,
    ): NotifyInterests {
        val fromCategories =
            properties.categories.flatMap { keywordCatalog.keywordsIn(TechCategory.from(it)) }
        val fromKeywords = properties.keywords.map { resolveKeyword(it, keywordCatalog.allKeywords()) }
        return NotifyInterests((fromCategories + fromKeywords).toSet())
    }

    /** 設定されたキーワード名を大文字小文字を無視して辞書の正規化名へ解決する。未知の名前は起動失敗。 */
    private fun resolveKeyword(configured: String, allKeywords: Set<String>): String =
        allKeywords.firstOrNull { it.equals(configured, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "invalid interest keyword: \"$configured\" (must be one of ${allKeywords.sorted()})",
            )
}
