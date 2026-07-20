package dev.rockyh.rsswatch.keywords.application

import dev.rockyh.rsswatch.capabilities.CncfMatchPort
import dev.rockyh.rsswatch.keywords.domain.CncfProjectMatcher
import dev.rockyh.rsswatch.shared.contract.CncfMention
import org.springframework.stereotype.Component

/**
 * [CncfMatchPort] の実装。domain の [CncfProjectMatcher] に委譲する。
 * Webhook 設定にはガードしない(Web レポートは通知機能と独立して常時有効)。
 */
@Component
class CncfMatchPortImpl(
    private val matcher: CncfProjectMatcher = CncfProjectMatcher(),
) : CncfMatchPort {

    override fun match(text: String): List<CncfMention> = matcher.match(text)
}
