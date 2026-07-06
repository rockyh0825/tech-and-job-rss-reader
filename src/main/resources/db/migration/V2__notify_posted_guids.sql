-- notify feature: 投稿済み guid の記録(日跨ぎの二重投稿防止。design.md「Data Models」参照)
-- posted_at は V1 の items と同じく TIMESTAMPTZ。
-- アプリは Instant を UTC の OffsetDateTime に変換してバインドする(RssItemRepository 参照)

CREATE TABLE notify_posted (
    guid TEXT PRIMARY KEY,
    posted_at TIMESTAMPTZ NOT NULL
);
