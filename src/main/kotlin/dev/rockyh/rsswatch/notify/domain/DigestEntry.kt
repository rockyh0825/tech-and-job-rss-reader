package dev.rockyh.rsswatch.notify.domain

/**
 * ダイジェスト 1 件分の表示内容(純 Kotlin)。application が組み立て、infrastructure が投稿する
 * feature 内共有の型。[summary] は AI 要約。要約に失敗した場合は null(要件 2.2 のフォールバック)。
 */
data class DigestEntry(
    val title: String,
    val url: String,
    val summary: String?,
    val keywords: List<String>,
)
