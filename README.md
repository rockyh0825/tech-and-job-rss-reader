# tech-and-job-rss-reader

技術記事と求人情報を RSS で収集し、**求人で言及されている技術**と**その技術の記事**をクロスリンクして眺められるツール。

Kotlin + Spring Boot + Apache Kafka によるイベント駆動構成で、自宅サーバーでの常駐運用を想定。あわせて Kafka・サーバーサイド Kotlin の学習題材を兼ねる。

## 構成(概要)

```
fetcher (@Scheduled + Rome + キーワード抽出)
   └─ publish → Kafka topic: rss.items
                   ├─ sink consumer  → PostgreSQL(冪等書き込み)→ レポート / 集計 API
                   └─ live consumer  → SSE → ブラウザ(リアルタイム新着)

notifier (@Scheduled デフォルト毎朝8:00 / CNCF ダイジェストは 8:10)
   ├─ デイリーダイジェスト: PostgreSQL(求人で言及の多い技術 × その技術の記事)集計
   │     → 上位技術+関連記事を選抜 → Claude で要約 → Discord へ投稿
   └─ CNCF ダイジェスト: CNCF フィードの新着記事 → 成熟度バッジ付きで専用チャンネルへ投稿
```

- **クロスリンク(目玉機能)**: 記事・求人から技術キーワードを辞書ベースで抽出し、「求人で言及回数の多い技術」ランキングと「その技術の記事」を一画面に並べる
- 同じ topic を sink / live の 2 つの consumer group が独立オフセットで読むのが構成の核

## クイックスタート(ローカル開発)

前提: JDK 21 / Docker(Compose v2)

```bash
# 1. 依存サービス(Kafka + PostgreSQL + kafka-ui + Prometheus + Grafana + Tempo)を起動
#    (topic rss.items は kafka-init が自動作成)
docker compose -f docker/docker-compose.yml up -d

# 2. アプリを起動(リポジトリ直下で。feeds.toml はカレントディレクトリを使う)
./gradlew bootRun
```

- ブラウザ UI: <http://localhost:8080>
- 集計 API: `GET http://localhost:8080/api/report?days=7`
- CNCF レポート API(CNCF 記事 × プロジェクト成熟度): `GET http://localhost:8080/api/cncf?days=7`
- kafka-ui(topic の中身の確認): <http://localhost:8081>
- Grafana(メトリクスのダッシュボード・トレースの閲覧): <http://localhost:3001>(Prometheus は <http://localhost:9090>。運用の要点は [docs/home-server.md](docs/home-server.md))

PostgreSQL のスキーマは初回起動時に Flyway が自動で作成する(DB 自体は compose の postgres サービスが用意する)。

パイプラインが一通り動いていることの確認手順は [docs/verification.md](docs/verification.md) を参照。

## フィードの追加

`feeds.toml` に追記するだけ。

```toml
[[feeds]]
name = "フィード名"
url = "https://example.com/feed"
category = "tech"   # "tech"(技術記事)/ "jobs"(求人)/ "cncf"(CNCF 関連。専用ダイジェストで配信)
```

> 自宅サーバーの `feeds.toml` はデプロイのたびにリポジトリの内容で上書きされる。サーバー上で直接編集せず、リポジトリ側を変更して main にマージすること。

## 運用ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/home-server.md](docs/home-server.md) | 自宅サーバーでの常駐運用(systemd + `/etc/rss-watch.env`)・観測(Prometheus + Grafana + Tempo)・自動デプロイ(GitHub Actions self-hosted runner) |
| [docs/notify.md](docs/notify.md) | デイリーダイジェスト・CNCF ダイジェスト(Discord 通知)の挙動と設定 |
| [docs/public-access.md](docs/public-access.md) | Cloudflare Tunnel + Access による外部公開と、オリジンでの JWT 検証(任意ハードニング) |
| [docs/verification.md](docs/verification.md) | ローカルでのパイプライン動作確認(手動 E2E)手順 |

## 設計ドキュメント

設計・アーキテクチャ・規約・プロダクト方針は spec-workflow を参照:

- [.spec-workflow/steering/product.md](.spec-workflow/steering/product.md) — プロダクト概要・MVP スコープ
- [.spec-workflow/steering/tech.md](.spec-workflow/steering/tech.md) — 技術スタック・アーキテクチャ・決定ログ要約
- [.spec-workflow/steering/structure.md](.spec-workflow/steering/structure.md) — ディレクトリ構成・レイヤー責務
- [.spec-workflow/specs/mvp-rss-pipeline/](.spec-workflow/specs/mvp-rss-pipeline/) — MVP の requirements / design / tasks
- [.spec-workflow/specs/postgres-migration/](.spec-workflow/specs/postgres-migration/) — SQLite → PostgreSQL 移行の requirements / design / tasks
- [.spec-workflow/specs/discord-notifier/](.spec-workflow/specs/discord-notifier/) — デイリーダイジェスト(Discord 通知)の requirements / design / tasks
- [.spec-workflow/specs/private-web-access/](.spec-workflow/specs/private-web-access/) — Cloudflare Tunnel + Access による外部公開の requirements / design / tasks
- [.spec-workflow/specs/observability/](.spec-workflow/specs/observability/) — Actuator + Prometheus + Grafana による観測の requirements / design / tasks
- [.spec-workflow/specs/distributed-tracing/](.spec-workflow/specs/distributed-tracing/) — Micrometer Tracing + Tempo による分散トレーシングの requirements / design / tasks

## ステータス

MVP 実装完了(Task 1〜12。内訳は [mvp-rss-pipeline/tasks.md](.spec-workflow/specs/mvp-rss-pipeline/tasks.md))。
SQLite → PostgreSQL 移行完了(内訳は [postgres-migration/tasks.md](.spec-workflow/specs/postgres-migration/tasks.md))。
デイリーダイジェスト(Discord 通知)実装完了(内訳は [discord-notifier/tasks.md](.spec-workflow/specs/discord-notifier/tasks.md))。
CNCF 特化ダイジェスト実装完了([issue #46](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/46))。
