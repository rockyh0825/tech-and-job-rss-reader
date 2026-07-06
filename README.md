# tech-and-job-rss-reader

技術記事と求人情報を RSS で収集し、**求人で言及されている技術**と**その技術の記事**をクロスリンクして眺められるツール。

Kotlin + Spring Boot + Apache Kafka によるイベント駆動構成で、自宅サーバーでの常駐運用を想定。あわせて Kafka・サーバーサイド Kotlin の学習題材を兼ねる。

## 構成(概要)

```
fetcher (@Scheduled + Rome + キーワード抽出)
   └─ publish → Kafka topic: rss.items
                   ├─ sink consumer  → PostgreSQL(冪等書き込み)→ レポート / 集計 API
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
# 1. Kafka + PostgreSQL + kafka-ui を起動(topic rss.items は kafka-init が自動作成)
docker compose -f docker/docker-compose.yml up -d

# 2. アプリを起動(リポジトリ直下で。feeds.toml はカレントディレクトリを使う)
./gradlew bootRun
```

- ブラウザ UI: <http://localhost:8080>
- 集計 API: `GET http://localhost:8080/api/report?days=7`
- kafka-ui(topic の中身の確認): <http://localhost:8081>

PostgreSQL のスキーマは初回起動時に Flyway が自動で作成する(DB 自体は compose の postgres サービスが用意する)。

### 自宅サーバー(常駐運用)

```bash
# 1. fat jar をビルド
./gradlew bootJar   # → build/libs/tech-and-job-rss-reader-0.0.1-SNAPSHOT.jar

# 2. 配置(例: /opt/rss-watch)
sudo mkdir -p /opt/rss-watch
sudo cp build/libs/tech-and-job-rss-reader-0.0.1-SNAPSHOT.jar /opt/rss-watch/rss-watch.jar
sudo cp feeds.toml /opt/rss-watch/

# 3. Kafka + PostgreSQL を起動(compose の restart: unless-stopped で再起動後も自動復帰)
docker compose -f docker/docker-compose.yml up -d

# 4. アプリを起動(接続先はデフォルトで compose のローカル値を使う)
cd /opt/rss-watch && java -jar rss-watch.jar
```

環境変数(すべて省略可。デフォルトは compose のローカル値):

| 変数 | 意味 | デフォルト |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka の接続先 | `localhost:9092` |
| `RSS_WATCH_DB_URL` | PostgreSQL の JDBC URL | `jdbc:postgresql://localhost:5432/rsswatch` |
| `RSS_WATCH_DB_USER` | PostgreSQL のユーザー名 | `rsswatch` |
| `RSS_WATCH_DB_PASSWORD` | PostgreSQL のパスワード | `rsswatch` |

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
Environment=RSS_WATCH_DB_URL=jdbc:postgresql://localhost:5432/rsswatch
Environment=RSS_WATCH_DB_USER=rsswatch
Environment=RSS_WATCH_DB_PASSWORD=rsswatch
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

## 動作確認(ローカル)

MVP のパイプライン(fetcher → Kafka → sink / live consumer → API / UI)が一通り動いていることをローカルで確認する手順。「ローカル開発」の 2 コマンド(`docker compose up` + `./gradlew bootRun`)を実行済みであることが前提。

なおアプリは正常時にはほとんどログを出さない(フィード取得失敗や publish 失敗時の WARN のみ)ため、動作確認はログではなく **kafka-ui・PostgreSQL・API** で行う。

### 0. 自動テスト

まず全テスト(単体テスト + EmbeddedKafka による結合テスト)が通ることを確認する。

```bash
./gradlew test
```

### 1. フィード巡回と Kafka への publish

fetcher は**起動の約 10 秒後に初回巡回**し、以降 15 分間隔で巡回する(`application.yml` の `rss-watch.fetch`)。起動から 1 分ほど待ってから確認するとよい。

- **kafka-ui で確認**: <http://localhost:8081> を開き、topic `rss.items` の Messages にメッセージが入っていることを確認する。key がフィード名になっており、同じフィードのアイテムが同じパーティションに載っていることも観察できる(3 パーティション)
- **CLI で確認する場合**:

```bash
docker exec rss-watch-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 --topic rss.items \
  --from-beginning --max-messages 3 --property print.key=true
```

### 2. PostgreSQL への保存(sink consumer)

sink consumer が Kafka から読んだアイテムを PostgreSQL に書き込んでいることを確認する。

```bash
# tech / jobs の両カテゴリでアイテムが保存されていること
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch \
  -c "SELECT category, COUNT(*) FROM items GROUP BY category;"

# 技術キーワードが辞書ベースで抽出されていること
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch \
  -c "SELECT keyword, COUNT(*) AS c FROM item_keywords GROUP BY keyword ORDER BY c DESC LIMIT 10;"
```

### 3. クロスリンクレポート API

目玉機能のクロスリンク(求人で言及されている技術 × その技術の記事)が返ってくることを確認する。

```bash
# 正常系: crossSections に技術キーワードごとの言及回数と記事が並ぶ
curl -s "http://localhost:8080/api/report?days=7" | python3 -m json.tool | head -40

# 異常系: days は 1〜365。範囲外は 400 Bad Request
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/report?days=0"
```

### 4. ブラウザ UI とリアルタイム新着(SSE)

- <http://localhost:8080> を開き、レポート(技術ランキングと記事のクロスリンク)が表示されることを確認する
- リアルタイム新着は SSE(`GET /api/stream`)で配信される。curl で直接確認する場合:

```bash
curl -N http://localhost:8080/api/stream
```

接続直後に `:connected` が届き、以降は新着があるたびに `event:item` + `data:{...}` が流れてくる。**手っ取り早く新着を見たい場合は、アプリを再起動して起動直後に接続する**とよい(約 10 秒後の初回巡回で全フィード分のアイテムがまとめて流れてくる)。定常運用では次の巡回(最大 15 分後)を待つ。

### 5. 冪等性と consumer group の独立オフセット

アプリを Ctrl-C で止めて `./gradlew bootRun` で再起動すると、fetcher が同じアイテムを再度 Kafka へ publish するが、**DB には重複保存されない**(guid を主キーとした冪等書き込み)。

```bash
# 再起動の前後で件数を比較する(純粋な新着分しか増えない)
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch -c "SELECT COUNT(*) FROM items;"
```

同じ topic を sink / live の 2 つの consumer group が独立オフセットで読んでいることも確認できる。

```bash
docker exec rss-watch-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group sink
# --group live に変えると live consumer 側のオフセットを確認できる
```

各パーティションの `LAG` が 0(= 溜まりなく消費できている)であることを確認する。

### 6. PostgreSQL の停止・再開に対する回復(sink の取りこぼしなし)

PostgreSQL が一時停止しても、sink は書き込み失敗でオフセットをコミットしないため、復旧後に再配信され冪等書き込みで重複なく回復する。

```bash
# 停止中も fetcher → Kafka は動き続ける(report API は 500 になる)
docker stop rss-watch-postgres

# 再開すると sink が溜まった分を catch-up し、件数が回復する
docker start rss-watch-postgres
```

### 7. 片付け・やり直し

```bash
# アプリは Ctrl-C で停止し、Kafka と PostgreSQL を止める
docker compose -f docker/docker-compose.yml down

# まっさらな状態からやり直す場合(Kafka と PostgreSQL のデータも消す)
docker compose -f docker/docker-compose.yml down -v
```

## 既存環境からの移行(SQLite → PostgreSQL)

MVP 時点の SQLite で運用していた環境向けの手順。**SQLite ファイルのデータ移行は行わない**。Kafka の topic `rss.items` に retention(デフォルト 7 日)内の全メッセージが残っているため、sink consumer group のオフセットを earliest にリセットすれば、空の PostgreSQL に catch-up で再蓄積される。

```bash
# 1. アプリを停止した状態で、compose を更新して PostgreSQL を起動
docker compose -f docker/docker-compose.yml up -d

# 2. sink group のオフセットを先頭にリセット(アプリ停止中に実行すること)
docker exec rss-watch-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 \
  --group sink --topic rss.items --reset-offsets --to-earliest --execute

# 3. アプリを起動(sink が topic の先頭から PostgreSQL へ再蓄積する)
```

- retention を超えた古いメッセージは失われる(個人ツールとして許容)
- live consumer group はリセットしない(過去分を SSE に流し直す意味がないため)
- 旧 `rss-watch.db`(SQLite ファイル)と環境変数 `RSS_WATCH_DB_PATH` は不要になったので削除してよい

## ステータス

MVP 実装完了(Task 1〜12。内訳は [mvp-rss-pipeline/tasks.md](.spec-workflow/specs/mvp-rss-pipeline/tasks.md))。
SQLite → PostgreSQL 移行完了(内訳は [postgres-migration/tasks.md](.spec-workflow/specs/postgres-migration/tasks.md))。
