package dev.rockyh.rsswatch.notify.domain

/** 記事ページのサムネイル画像を解決する抽象(実装は infrastructure の OgpThumbnailResolver)。 */
interface ThumbnailResolver {

    /**
     * 記事ページのサムネイル画像 URL を返す。画像が無い・取得や解析に失敗した場合は null
     * (呼び出し側がサムネイルなしでフォールバックできる)。
     */
    fun resolve(articleUrl: String): String?
}
