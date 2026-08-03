package dev.rockyh.rsswatch.notify.domain

/**
 * Discord からフィードバック(リアクション・返信)を読み取る抽象
 * (実装は infrastructure の DiscordBotClient。Bot トークンでの REST 読み取り)。
 */
interface DiscordFeedbackSource {

    /**
     * ダイジェスト投稿 1 通のリアクション現在値を返す。
     * メッセージが削除済み(見つからない)場合は null(呼び出し側がスナップショットを消さずに済むよう、
     * 0 件の空リストと区別する)。取得の失敗は例外のまま伝播する。
     */
    fun reactions(channelId: String, messageId: String): List<ReactionCount>?

    /**
     * チャンネルの直近メッセージから、[messageIds](ダイジェスト投稿)への返信を返す。
     * Discord の返信機能で message_reference が付いたもののみで、bot による返信は除く。
     * 遡れる範囲は直近 100 通まで(Discord API の 1 リクエスト上限)。
     */
    fun repliesTo(channelId: String, messageIds: Set<String>): List<DiscordReply>
}
