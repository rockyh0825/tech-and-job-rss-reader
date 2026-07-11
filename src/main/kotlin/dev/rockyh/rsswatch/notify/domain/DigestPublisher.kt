package dev.rockyh.rsswatch.notify.domain

/** ダイジェストを外部へ 1 通投稿する抽象(実装は infrastructure の DiscordWebhookClient)。 */
interface DigestPublisher {

    /** 技術ごとのグループ一式を 1 通にまとめて投稿する。失敗時は [Result.failure]。 */
    fun post(digests: List<TechDigest>): Result<Unit>
}
