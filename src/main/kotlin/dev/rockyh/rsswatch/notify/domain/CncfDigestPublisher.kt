package dev.rockyh.rsswatch.notify.domain

/**
 * CNCF ダイジェストを外部へ投稿する抽象(実装は infrastructure の CncfDiscordWebhookClient)。
 * 投稿のセマンティクス(記事ごとに 1 通・最後に導線・[PostOutcome] で結果を返す)は
 * [DigestPublisher] と同じ。
 */
interface CncfDigestPublisher {

    fun post(entries: List<CncfDigestEntry>): PostOutcome
}
