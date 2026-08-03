-- notify feature: Discord 上のフィードバック(リアクション・返信)を学習データとして集めるためのテーブル群(issue #72)。
-- verdict への解釈はここでは行わず、生データのまま保存する(解釈は #70 Step 4 で後から決める)。
-- タイムスタンプは V1 の items と同じく TIMESTAMPTZ。アプリは Instant を UTC の OffsetDateTime に
-- 変換してバインドする(RssItemRepository 参照)

-- 投稿済みダイジェスト記事と Discord メッセージの対応(guid ↔ channel_id + message_id)。
-- Webhook POST に ?wait=true を付けて得たレスポンスから記録する(DiscordMessageRepository 参照)
CREATE TABLE notify_discord_message (
    message_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    guid TEXT NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL
);

-- リアクションの現在値スナップショット(message_id × 絵文字ごとの件数)。
-- 回収ジョブがメッセージ単位で全置き換えするため、リアクションの取り消しも自然に反映される
CREATE TABLE notify_reaction (
    message_id TEXT NOT NULL,
    emoji TEXT NOT NULL,
    reaction_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (message_id, emoji)
);

-- ダイジェスト投稿への返信(Discord の返信機能で message_reference が付いたもののみ)。
-- reply_message_id(返信メッセージ自身の ID)で upsert し、本文編集は上書きで追従する
CREATE TABLE notify_reply (
    reply_message_id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL,
    content TEXT NOT NULL,
    replied_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL
);
