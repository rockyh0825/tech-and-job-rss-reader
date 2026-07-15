package dev.rockyh.rsswatch.notify.domain

/**
 * 興味のある技術の集合(設定から解決済みの正規化名)。
 * ダイジェスト編成でランキングより優先して扱う([DigestSelectionPolicy])。
 */
data class NotifyInterests(val keywords: Set<String>) {

    /** [keyword](正規化名)が興味の対象かを返す。 */
    fun isInterested(keyword: String): Boolean = keyword in keywords
}
