package dev.rockyh.rsswatch.notify.domain

/** タイトル + 概要から要約を生成する抽象(実装は infrastructure の ClaudeSummarizer)。 */
interface Summarizer {

    /** 要約を返す。失敗時は [Result.failure](呼び出し側が要約なしでフォールバックできる)。 */
    fun summarize(title: String, summary: String): Result<String>
}
