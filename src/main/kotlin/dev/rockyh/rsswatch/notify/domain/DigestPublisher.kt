package dev.rockyh.rsswatch.notify.domain

/** ダイジェストを外部へ投稿する抽象(実装は infrastructure の DiscordWebhookClient)。 */
interface DigestPublisher {

    /**
     * 記事 1 件につき 1 通ずつ投稿し、最後まで投稿し終えて 1 件以上投稿できたときだけ、
     * 最後にサイト導線を 1 通投稿する。どこまで投稿できたかを [PostOutcome] で返す(例外は投げない)。
     *
     * 個別の記事が受け付けられない場合はその記事だけスキップして次へ進み、Discord へ到達できない場合は
     * そこで投稿を打ち切る(分類の詳細は実装の DiscordWebhookClient を参照)。
     */
    fun post(digests: List<TechDigest>): PostOutcome

    /**
     * 候補 0 件の日に、記事なしでサイト導線だけを 1 通投稿する。
     * 記事は無いので [PostOutcome.postedGuids] は常に空(失敗は [PostOutcome.failure] で返す)。
     */
    fun postCtaOnly(): PostOutcome
}

/**
 * 投稿の結果。記事ごとに 1 通ずつ投稿するため「全部成功 / 全部失敗」の 2 値では表せず、
 * **実際に投稿できた記事**を [postedGuids] に投稿順で返す。
 *
 * 呼び出し側は [postedGuids] だけを通知済みとして記録すればよい。こうすることで、途中で失敗しても
 * 投稿済みの記事が翌日に重複投稿されず、未投稿の記事が取りこぼされることもない。
 *
 * [failure] は投稿を**打ち切らせた**原因(最後まで投稿し終えた場合は null)。個別にスキップされた記事は
 * ここには現れず(最後まで走り切れば失敗は無い)、実装側の warn ログで可視化される。
 *
 * 導線のみの投稿([DigestPublisher.postCtaOnly] 等)では記事が無いため [postedGuids] は常に空。
 *
 * [postedMessages] は投稿できた記事と Discord メッセージの対応(リアクション・返信の回収用)。
 * `?wait=true` のレスポンスからメッセージを解析できた記事だけが載る = [postedGuids] の部分集合で、
 * 解析に失敗しても投稿自体の成否([postedGuids] / [failure])には影響しない。
 */
data class PostOutcome(
    val postedGuids: List<String>,
    val failure: Throwable? = null,
    val postedMessages: List<DiscordMessageRef> = emptyList(),
)
