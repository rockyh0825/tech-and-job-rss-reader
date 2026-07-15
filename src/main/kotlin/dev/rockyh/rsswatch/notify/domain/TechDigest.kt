package dev.rockyh.rsswatch.notify.domain

/**
 * 求人で言及された技術 1 件と、その技術に関連する記事(要約付き)。Discord 通知の 1 グループ(純 Kotlin)。
 * application が組み立て、infrastructure が投稿する feature 内共有の型。
 */
data class TechDigest(
    val keyword: String,
    val mentionCount: Int,
    val articles: List<DigestArticle>,
)

/**
 * 通知に載せる記事 1 件。[summary] は AI 要約。要約に失敗した場合は null(見出しごと省いてフォールバック)。
 *
 * [guid] は元の RSS アイテムの識別子。記事ごとに 1 通ずつ投稿するため、どこまで投稿できたかを
 * 呼び出し側へ返す([PostOutcome.postedGuids])のに使う。
 */
data class DigestArticle(
    val guid: String,
    val title: String,
    val url: String,
    val summary: String?,
)
