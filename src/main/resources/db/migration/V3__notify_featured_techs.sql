-- notify feature: 技術ごとの最終紹介日時の記録(ダイジェストのローテーション用)。
-- notify_posted(guid 単位・永久除外)と違い、keyword 単位で last_featured_at を最新へ上書きする。
-- アプリは Instant を UTC の OffsetDateTime に変換してバインドする(FeaturedTechRepository 参照)

CREATE TABLE notify_featured_techs (
    keyword TEXT PRIMARY KEY,
    last_featured_at TIMESTAMPTZ NOT NULL
);
