package dev.rockyh.rsswatch.notify.domain

/**
 * 求人で言及された技術 1 件と、その技術に関連する記事(要約付き)。Discord 通知の 1 グループ(純 Kotlin)。
 * application が組み立て、infrastructure が投稿する feature 内共有の型。
 * [mentionCount] は 0 になり得る(求人に出ていない興味技術を記事だけで載せる場合)。
 */
data class TechDigest(
    val keyword: String,
    val mentionCount: Int,
    val articles: List<DigestArticle>,
    val interested: Boolean = false,
)

/**
 * 通知に載せる記事 1 件。[summary] は AI 要約。要約に失敗した場合は null(見出しごと省いてフォールバック)。
 */
data class DigestArticle(
    val title: String,
    val url: String,
    val summary: String?,
)
