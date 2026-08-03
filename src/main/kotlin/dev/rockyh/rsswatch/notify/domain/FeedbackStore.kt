package dev.rockyh.rsswatch.notify.domain

import java.time.Instant

/** メッセージに付いたリアクションの絵文字と件数(取得時点のスナップショット)。 */
data class ReactionCount(
    val emoji: String,
    val count: Int,
)

/** ダイジェスト投稿への返信(Discord の返信機能で message_reference が付いたもの)。 */
data class DiscordReply(
    /** 返信メッセージ自身の ID(upsert のキー)。 */
    val replyMessageId: String,
    /** 参照先 = ダイジェスト投稿の message ID。 */
    val referencedMessageId: String,
    val authorId: String,
    val authorName: String,
    /** 返信本文。Bot の Message Content Intent が無効だと空文字で届く。 */
    val content: String,
    val repliedAt: Instant,
)

/**
 * Discord 上のフィードバック(リアクション・返信)の生データ保存の抽象
 * (実装は infrastructure の DiscordFeedbackRepository)。
 *
 * verdict(GOOD/BAD 等)への解釈はここでは行わず、取得できた事実をそのまま保存する。
 * 解釈は #70 Step 4 のスコアリング導入時に、貯まったデータを見てから決める。
 */
interface FeedbackStore {

    /**
     * メッセージのリアクションを取得時点のスナップショットで置き換える。
     * 前回あった絵文字が [reactions] に無ければ削除する(リアクションの取り消しが自然に反映される)。
     */
    fun replaceReactions(messageId: String, reactions: List<ReactionCount>)

    /** メッセージのリアクションの現在値を返す。 */
    fun reactions(messageId: String): List<ReactionCount>

    /** 返信を記録する。同じ返信([DiscordReply.replyMessageId])は上書きし、本文編集に追従する。 */
    fun recordReplies(replies: List<DiscordReply>)

    /** ダイジェスト投稿 [messageId] への返信を返す。 */
    fun repliesTo(messageId: String): List<DiscordReply>
}
