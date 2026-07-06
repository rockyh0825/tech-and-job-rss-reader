-- archive feature の初期スキーマ(design.md「Data Models」参照)
-- published_at / fetched_at は TIMESTAMPTZ(マイクロ秒精度)。
-- アプリは Instant を UTC の OffsetDateTime に変換してバインドする(RssItemRepository 参照)

CREATE TABLE items (
    guid TEXT PRIMARY KEY,
    feed_name TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    summary TEXT,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE item_keywords (
    guid TEXT NOT NULL,
    keyword TEXT NOT NULL,
    PRIMARY KEY (guid, keyword)
);

CREATE INDEX idx_items_category ON items (category);

CREATE INDEX idx_item_keywords_keyword ON item_keywords (keyword);
