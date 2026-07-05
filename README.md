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

## 起動手順

前提: JDK 21 / Docker(Compose v2)

### ローカル開発

```bash
# 1. Kafka + kafka-ui を起動(topic rss.items は kafka-init が自動作成)
docker compose -f docker/docker-compose.yml up -d

# 2. アプリを起動(リポジトリ直下で。feeds.toml と SQLite はカレントディレクトリを使う)
./gradlew bootRun
```

- ブラウザ UI: <http://localhost:8080>
- 集計 API: `GET http://localhost:8080/api/report?days=7`
- kafka-ui(topic の中身の確認): <http://localhost:8081>

SQLite は初回起動時に Flyway が自動でスキーマを作成する(デフォルト `./rss-watch.db`)。

### 自宅サーバー(常駐運用)

```bash
# 1. fat jar をビルド
./gradlew bootJar   # → build/libs/tech-and-job-rss-reader-0.0.1-SNAPSHOT.jar

# 2. 配置(例: /opt/rss-watch)
sudo mkdir -p /opt/rss-watch/data
sudo cp build/libs/tech-and-job-rss-reader-0.0.1-SNAPSHOT.jar /opt/rss-watch/rss-watch.jar
sudo cp feeds.toml /opt/rss-watch/

# 3. Kafka を起動(compose の restart: unless-stopped で再起動後も自動復帰)
docker compose -f docker/docker-compose.yml up -d

# 4. アプリを起動(DB パスは systemd unit 例と合わせておく)
cd /opt/rss-watch && RSS_WATCH_DB_PATH=/opt/rss-watch/data/rss-watch.db java -jar rss-watch.jar
```

環境変数(どちらも省略可):

| 変数 | 意味 | デフォルト |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka の接続先 | `localhost:9092` |
| `RSS_WATCH_DB_PATH` | SQLite ファイルのパス(親ディレクトリは事前に作成しておく) | `rss-watch.db`(カレントディレクトリ) |

### systemd で自動再起動する場合の unit 例

`/etc/systemd/system/rss-watch.service`:

```ini
[Unit]
Description=RSS Watch (tech-and-job-rss-reader)
# Kafka(docker)より後に起動させる。落ちても Restart=always で拾えるので依存は緩めでよい
After=network-online.target docker.service
Wants=network-online.target

[Service]
WorkingDirectory=/opt/rss-watch
ExecStart=/usr/bin/java -jar /opt/rss-watch/rss-watch.jar
Environment=RSS_WATCH_DB_PATH=/opt/rss-watch/data/rss-watch.db
Environment=KAFKA_BOOTSTRAP_SERVERS=localhost:9092
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now rss-watch
```

Kafka が一時停止していてもアプリは落ちない(producer/consumer がバックグラウンドで再接続し、fetcher は次周期で再巡回する)。

## ステータス

MVP 実装完了(Task 1〜12)。実装タスクの内訳は [tasks.md](.spec-workflow/specs/mvp-rss-pipeline/tasks.md) を参照。
