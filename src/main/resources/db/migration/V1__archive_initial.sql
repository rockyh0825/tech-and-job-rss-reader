-- archive feature の初期スキーマ(design.md「Data Models」参照)
-- published_at / fetched_at は固定桁の ISO-8601 UTC(ナノ秒 9 桁)の TEXT。
-- 桁が固定なので辞書順比較 = 時系列比較が成立する(RssItemRepository が書式を保証する)

CREATE TABLE items (
    guid TEXT PRIMARY KEY,
    feed_name TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    summary TEXT,
    published_at TEXT,
    fetched_at TEXT NOT NULL
);

CREATE TABLE item_keywords (
    guid TEXT NOT NULL,
    keyword TEXT NOT NULL,
    PRIMARY KEY (guid, keyword)
);

CREATE INDEX idx_items_category ON items (category);

CREATE INDEX idx_item_keywords_keyword ON item_keywords (keyword);
