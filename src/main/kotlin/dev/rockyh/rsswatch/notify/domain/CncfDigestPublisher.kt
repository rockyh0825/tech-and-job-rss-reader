package dev.rockyh.rsswatch.notify.domain

/**
 * CNCF ダイジェストを外部へ投稿する抽象(実装は infrastructure の CncfDiscordWebhookClient)。
 * 投稿のセマンティクス(記事ごとに 1 通・最後に導線・[PostOutcome] で結果を返す)は
 * [DigestPublisher] と同じ。
 */
interface CncfDigestPublisher {

    fun post(entries: List<CncfDigestEntry>): PostOutcome

    /**
     * 候補 0 件の日に、記事なしで導線だけを投稿する。サイト導線(通常ダイジェストと同じ)と
     * CNCF プロジェクト一覧への導線の 2 embeds を 1 通で送る(部分失敗を作らない)。
     * 記事は無いので [PostOutcome.postedGuids] は常に空(失敗は [PostOutcome.failure] で返す)。
     */
    fun postCtaOnly(): PostOutcome
}
