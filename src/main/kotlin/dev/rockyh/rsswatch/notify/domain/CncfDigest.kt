package dev.rockyh.rsswatch.notify.domain

import dev.rockyh.rsswatch.shared.contract.CncfMention

/**
 * CNCF ダイジェストに載せる記事 1 件(純 Kotlin)。application が組み立て、infrastructure が投稿する。
 * [mentions] は記事中で言及された CNCF プロジェクト(成熟度の低い順に整列済み。言及なしなら空)。
 * 記事本体([DigestArticle])は既存ダイジェストと共有の型を再利用する。
 */
data class CncfDigestEntry(
    val article: DigestArticle,
    val mentions: List<CncfMention>,
)
