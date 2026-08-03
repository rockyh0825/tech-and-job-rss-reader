package dev.rockyh.rsswatch.notify.domain

import java.time.Instant

/**
 * 投稿済みダイジェスト記事と Discord メッセージの対応(guid ↔ channel_id + message_id)。
 * リアクション・返信の回収時に「どのメッセージがどの記事か」を引くための参照。
 */
data class DiscordMessageRef(
    val guid: String,
    val channelId: String,
    val messageId: String,
)

/** 投稿済み Discord メッセージの記録と照会の抽象(実装は infrastructure の DiscordMessageRepository)。 */
interface DigestMessageStore {

    /** 投稿できたメッセージの対応を現在時刻で記録する(同じ message_id の再投入は無害化する)。 */
    fun record(messages: List<DiscordMessageRef>)

    /** [since] 以降に記録したメッセージの対応を返す(境界は含む)。 */
    fun messagesSince(since: Instant): List<DiscordMessageRef>
}
