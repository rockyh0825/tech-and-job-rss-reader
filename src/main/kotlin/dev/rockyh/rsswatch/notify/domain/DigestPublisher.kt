package dev.rockyh.rsswatch.notify.domain

/** ダイジェストを外部へ投稿する抽象(実装は infrastructure の DiscordWebhookClient)。 */
interface DigestPublisher {

    /**
     * 記事 1 件につき 1 通ずつ投稿し、全記事を投稿できたときだけ最後にサイト導線を 1 通投稿する。
     * どこまで投稿できたかを [PostOutcome] で返す(例外は投げない)。
     */
    fun post(digests: List<TechDigest>): PostOutcome
}

/**
 * 投稿の結果。記事ごとに 1 通ずつ投稿するため「全部成功 / 全部失敗」の 2 値では表せず、
 * **実際に投稿できた記事**を [postedGuids] に投稿順で返す。
 *
 * 呼び出し側は [postedGuids] だけを通知済みとして記録すればよい。こうすることで、途中で失敗しても
 * 投稿済みの記事が翌日に重複投稿されず、未投稿の記事が取りこぼされることもない。
 *
 * [failure] は投稿を中断させた原因(全記事を投稿できた場合は null)。
 */
data class PostOutcome(
    val postedGuids: List<String>,
    val failure: Throwable? = null,
)
