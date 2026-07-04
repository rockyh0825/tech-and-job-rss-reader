# tech-and-job-rss-reader

技術記事と求人情報を RSS で収集し、**求人で言及されている技術**と**その技術の記事**をクロスリンクして眺められるツール。

Kotlin + Spring Boot + Apache Kafka によるイベント駆動構成で、自宅サーバーでの常駐運用を想定。あわせて Kafka・サーバーサイド Kotlin の学習題材を兼ねる。

## 構成(概要)

```
fetcher (@Scheduled + Rome + キーワード抽出)
   └─ publish → Kafka topic: rss.items
                   ├─ sink consumer  → SQLite(冪等書き込み)→ レポート / 集計 API
                   └─ live consumer  → SSE → ブラウザ(リアルタイム新着)
```

- **クロスリンク(目玉機能)**: 記事・求人から技術キーワードを辞書ベースで抽出し、「求人で言及回数の多い技術」ランキングと「その技術の記事」を一画面に並べる
- 同じ topic を sink / live の 2 つの consumer group が独立オフセットで読むのが構成の核

詳細は spec-workflow のドキュメントを参照:

- [.spec-workflow/steering/product.md](.spec-workflow/steering/product.md) — プロダクト概要・MVP スコープ
- [.spec-workflow/steering/tech.md](.spec-workflow/steering/tech.md) — 技術スタック・アーキテクチャ・決定ログ要約
- [.spec-workflow/steering/structure.md](.spec-workflow/steering/structure.md) — ディレクトリ構成・レイヤー責務
- [.spec-workflow/specs/mvp-rss-pipeline/](.spec-workflow/specs/mvp-rss-pipeline/) — MVP の requirements / design / tasks

## フィードの追加

`feeds.toml` に追記するだけ。

```toml
[[feeds]]
name = "フィード名"
url = "https://example.com/feed"
category = "tech"   # または "jobs"
```

## ステータス

spec 駆動で実装前の段階。実装タスクは [tasks.md](.spec-workflow/specs/mvp-rss-pipeline/tasks.md) を参照。
