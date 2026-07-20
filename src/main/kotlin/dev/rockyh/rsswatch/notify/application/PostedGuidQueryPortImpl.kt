package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.notify.domain.PostedGuidStore
import java.time.Instant
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * [PostedGuidQueryPort] の実装。domain の [PostedGuidStore](実体は PostedGuidRepository)に委譲する。
 *
 * PostedGuidStore の Bean は notify 有効時(webhook URL 設定時)のみ登録されるため
 * [ObjectProvider] で受け、notify 無効時は「配信済みなし」= 空集合を返す。
 */
@Component
class PostedGuidQueryPortImpl(
    private val postedGuidStore: ObjectProvider<PostedGuidStore>,
) : PostedGuidQueryPort {

    override fun postedGuids(): Set<String> =
        postedGuidStore.ifAvailable?.postedGuids(Instant.EPOCH) ?: emptySet()
}
