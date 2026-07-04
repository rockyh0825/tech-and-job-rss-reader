package dev.rockyh.rsswatch.capabilities

/**
 * 技術キーワード抽出の Port(feature 間境界)。
 *
 * - 依存する側: fetch/application(FetchFeedsUseCase が publish 前の抽出に使う)
 * - 実装する側: keywords/application(KeywordExtractionPortImpl)
 */
interface KeywordExtractionPort {

    /** タイトル + 概要などのテキストから、正規化済み技術キーワードの集合を返す。 */
    fun extract(text: String): Set<String>
}
