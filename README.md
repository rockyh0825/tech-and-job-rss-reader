# tech-and-job-rss-reader

技術記事と求人情報を RSS で収集し、**求人で言及されている技術**と**その技術の記事**をクロスリンクして眺められるツール。

Kotlin + Spring Boot + Apache Kafka によるイベント駆動構成で、自宅サーバーでの常駐運用を想定。あわせて Kafka・サーバーサイド Kotlin の学習題材を兼ねる。

## 構成(概要)

```
fetcher (@Scheduled + Rome + キーワード抽出)
   └─ publish → Kafka topic: rss.items
                   ├─ sink consumer  → PostgreSQL(冪等書き込み)→ レポート / 集計 API
                   └─ live consumer  → SSE → ブラウザ(リアルタイム新着)

notifier (@Scheduled 毎朝8:00)
   └─ PostgreSQL(求人で言及の多い技術 × その技術の記事)集計 → 上位技術+関連記事を選抜 → Claude で要約 → Discord へ1通投稿
```

- **クロスリンク(目玉機能)**: 記事・求人から技術キーワードを辞書ベースで抽出し、「求人で言及回数の多い技術」ランキングと「その技術の記事」を一画面に並べる
- 同じ topic を sink / live の 2 つの consumer group が独立オフセットで読むのが構成の核

詳細は spec-workflow のドキュメントを参照:

- [.spec-workflow/steering/product.md](.spec-workflow/steering/product.md) — プロダクト概要・MVP スコープ
- [.spec-workflow/steering/tech.md](.spec-workflow/steering/tech.md) — 技術スタック・アーキテクチャ・決定ログ要約
- [.spec-workflow/steering/structure.md](.spec-workflow/steering/structure.md) — ディレクトリ構成・レイヤー責務
- [.spec-workflow/specs/mvp-rss-pipeline/](.spec-workflow/specs/mvp-rss-pipeline/) — MVP の requirements / design / tasks
- [.spec-workflow/specs/postgres-migration/](.spec-workflow/specs/postgres-migration/) — SQLite → PostgreSQL 移行の requirements / design / tasks
- [.spec-workflow/specs/discord-notifier/](.spec-workflow/specs/discord-notifier/) — デイリーダイジェスト(Discord 通知)の requirements / design / tasks
- [.spec-workflow/specs/private-web-access/](.spec-workflow/specs/private-web-access/) — Cloudflare Tunnel + Access による外部公開の requirements / design / tasks

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

## 自動デプロイ(GitHub Actions self-hosted runner)

main へ push(PR マージ)されると、GitHub ホストの runner でビルド・テストした jar を、自宅サーバー常駐の self-hosted runner が受け取って `/opt/rss-watch/` に配置し、`rss-watch.service` を再起動する(`.github/workflows/ci.yml` の `deploy` ジョブ)。runner は GitHub へ**アウトバウンド**で long-poll するだけなので、ポート開放は不要。

### サーバー側の初回セットアップ

```bash
# 1. runner を配置ディレクトリに書き込めるユーザー(rocky)でインストール
#    GitHub → リポジトリの Settings → Actions → Runners → New self-hosted runner の
#    表示手順どおりに config.sh まで実行する(--url と --token はそこに表示される)
mkdir ~/actions-runner && cd ~/actions-runner
# (curl でダウンロード → tar 展開 → ./config.sh --url ... --token ...)

# 2. systemd サービスとして常駐させる
sudo ./svc.sh install rocky
sudo ./svc.sh start

# 3. 配置先を runner ユーザーが書き込めるようにする
sudo chown -R rocky:rocky /opt/rss-watch

# 4. サービス再起動だけパスワードなし sudo を許可する
echo 'rocky ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart rss-watch' | sudo tee /etc/sudoers.d/rss-watch-deploy
sudo chmod 440 /etc/sudoers.d/rss-watch-deploy
```

### セキュリティ設定(public リポジトリでは必須)

このリポジトリは public のため、フォークからの PR が workflow を書き換えて self-hosted runner 上でコードを実行するのを防ぐ必要がある。リポジトリの **Settings → Actions → General → Fork pull request workflows from outside collaborators** で **「Require approval for all outside collaborators」** を選択すること(外部からの PR は承認するまで workflow が一切走らなくなる)。`deploy` ジョブ自体も `push` + `main` のときだけ動く条件になっており、PR では self-hosted runner を使わない。

> feeds.toml もデプロイのたびにリポジトリの内容で上書きされる。フィードの追加・変更はサーバー上で直接編集せず、リポジトリ側を変更して main にマージすること。

## 外部公開(Cloudflare Tunnel + Access)

自宅サーバーの Web UI(クロスリンク表示 + SSE 新着)を、**自分と許可した数人だけ**がアクセスできる状態でインターネット公開する手順。**アプリ本体は無改造**で、認証・TLS 終端・アクセス制御はすべて Cloudflare エッジで行う。ルーターのポート開放・グローバル IP 公開・DDNS のいずれも不要(`cloudflared` が Cloudflare へ**アウトバウンド**でトンネルを張るため)。

```
ブラウザ(許可メールのみ)
   └─ HTTPS → Cloudflare エッジ(Access 認証ゲート + TLS 終端)
                └─ 認証通過のみ → Tunnel → cloudflared(自宅サーバー常駐)
                                              └─ http://localhost:8080 → rss-watch(無改造)
```

詳細な設計・要件は [private-web-access spec](.spec-workflow/specs/private-web-access/) を参照。

### 前提(無料枠の条件)

- Cloudflare アカウントと Zero Trust(Free プラン)組織。ソフトウェアと Zero Trust Free は無料だが、**DNS を Cloudflare に向けたドメインが 1 つ必要**(ドメイン登録費用は別途。既存所有ドメインでも可)
- Free プランは **50 ユーザーまで無料**。それを超えると有料($7/user/月)。本ツールの想定(数人)は無料枠に収まる

### 手順

**1. ドメインを Cloudflare に向け、公開ホスト名を決める**

Cloudflare に登録したゾーンで、公開ホスト名(例 `rss.example.com`)を決める。この段階ではまだトンネル/Access は作らない。

**2. named tunnel を作成しトークンを取得**

Zero Trust ダッシュボード → **Networks → Tunnels** で named tunnel を作成し、**Public Hostname** に以下を登録する。

| 項目 | 値 |
|---|---|
| Subdomain / Domain | `rss.example.com`(手順 1 で決めたホスト名) |
| Service | `http://localhost:8080` |

作成時に表示される**トンネルトークン**を控える。これは機密情報なので**リポジトリにコミットしない**。

**3. cloudflared を自宅サーバーに常駐させる**

トークンを `docker/.env` に置き、`tunnel` profile を付けて起動する(`docker/.env` は `.gitignore` 済み)。

```bash
cp docker/.env.example docker/.env
# docker/.env の CLOUDFLARE_TUNNEL_TOKEN を手順 2 のトークンに差し替える
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile tunnel up -d
```

- `--profile tunnel` を付けたときだけ `cloudflared` が起動する(通常のローカル開発 `docker compose up -d` では起動しない)
- `--env-file docker/.env` を明示しておくと、実行ディレクトリや Compose のバージョンに依存せず確実に `docker/.env` が読まれる(推奨)。`-f docker/docker-compose.yml` 指定時は既定でも `docker/.env` が読まれるが、明示しておくと安全
- `restart: unless-stopped` により、サーバー再起動後も自動でトンネルが復帰する
- `cloudflared` のイメージはタグ固定(`cloudflare/cloudflared:<version>`)。更新は `docker-compose.yml` のタグを上げて `up -d` し直す
- 本 compose 構成は **Linux ホスト前提**(cloudflared を `network_mode: host` で動かし、ホスト上のアプリの `:8080` に到達させる)。macOS/Windows の Docker Desktop では同挙動にならない点に注意

> **順序に注意**: 手順 4(Access アプリ + ポリシー)を**先に作成・有効化してから**この cloudflared 起動で公開を live にすること。ポリシー未設定のまま公開すると、URL を知る第三者が一時的に無認証でオリジンに到達しうる(要件 1.1)。

> systemd で常駐させたい場合は compose の代わりに `cloudflared service install <TOKEN>` でもよい(install が systemd unit の作成・起動まで行う。`systemctl status cloudflared` で稼働を確認)。

**4. Cloudflare Access アプリ + ポリシーを設定**

Zero Trust ダッシュボード → **Access → Applications** で **Self-hosted** アプリを作成する。

- **Application domain**: 公開ホスト名 `rss.example.com`
- **Policy**: Action=Allow、Include=**Emails**(許可する数人のアドレス)または **Emails ending in**(信頼ドメイン)
- **認証方式**: 既定は **One-time PIN**(メール OTP)。必要なら Google / GitHub 等の IdP を追加
- **Session Duration**: 有効期間を設定(例 24h)。通過後は `CF_Authorization` Cookie でセッション維持

許可メールの追加・削除は Policy 編集だけで**即時反映**され、アプリの再起動もコード変更も不要。

### 動作確認(手動 E2E)

公開後、以下を確認する。

- **許可アカウント**: 公開 URL → Access 認証 → クロスリンク UI 表示 → SSE 新着受信 まで通ること(`EventSource` は Cookie を自動送出するため、初回にブラウザで認証済みなら SSE も追加操作なしで通る)
- **許可外アカウント**: Access で拒否され、UI・API がオリジンに到達しないこと
- **ポリシー即時反映**: 許可メールを 1 件追加/削除し、アプリ再起動なしで反映されること
- **フェイルセーフ**: `cloudflared` を停止すると公開だけ止まり、LAN 内アクセス・パイプライン(fetch → Kafka → sink/live/notify)が継続すること

### 公開前チェックリスト

- [ ] トンネルトークンを**コミットしない**(`docker/.env` は `.gitignore` 済み。`git status` で追跡されていないことを確認)
- [ ] ルーターの**ポート開放・ポートフォワードをしない**(`cloudflared` のアウトバウンドのみ)
- [ ] アプリの `:8080` はインターネットに直接公開せず、唯一の経路をトンネル + Access に限定する
- [ ] Access Policy の**許可メールを最小限**にする(必要な数人のみ)
- [ ] グローバル IP を公開しない(DNS はトンネルの CNAME を指す)

> **注意**: Cloudflare Access を経由しない経路(自宅 LAN からの `:8080` 直アクセス)は認証されない。これは現状の信頼レベル(自宅 LAN 内)と同じで個人ツールとして許容する。より厳格にしたい場合は、下記「任意ハードニング」でオリジン側の JWT 検証を有効化する。

### 任意ハードニング: オリジンでの JWT 検証(防御多層化)

自宅 LAN からの `:8080` 直アクセスも塞ぎたい場合、アプリ側で Cloudflare Access が付与する `Cf-Access-Jwt-Assertion`(署名済み JWT)を JWKS で検証し、**Access を経由しない全リクエストを 401 で拒否**できる。

**設定した時だけ有効**になる(未設定なら従来どおり無認証で起動)。有効化には Access アプリの **AUD タグ**(手順 4 のアプリ詳細に表示)と **team ドメイン**が必要。

```bash
export RSS_WATCH_ACCESS_TEAM_DOMAIN="myteam.cloudflareaccess.com"
export RSS_WATCH_ACCESS_AUD="<Access アプリの AUD タグ>"
java -jar rss-watch.jar
```

| 変数 | 意味 | デフォルト |
|---|---|---|
| `RSS_WATCH_ACCESS_TEAM_DOMAIN` | Zero Trust の team ドメイン(`https://` は省略可) | (未設定) |
| `RSS_WATCH_ACCESS_AUD` | Access アプリの AUD タグ。**設定時のみ検証が有効**(未設定=無効) | (未設定) |

- 検証内容: 署名(Cloudflare の JWKS で RS256 検証)・`aud`・`iss`(team ドメイン)・有効期限
- JWKS は `https://<team>/cdn-cgi/access/certs` から取得しキャッシュ・鍵ローテーションに追従する
- 有効化すると **LAN からの直アクセスも 401** になる。デバッグで直に叩きたい場合は環境変数を外して起動する

## デイリーダイジェスト(Discord 通知)

`notify` feature を有効にすると、毎朝 1 回「**求人で言及されている技術**」の上位を選び、各技術に「**その技術の記事**」を添えて Claude 要約付きで Discord に 1 通投稿する(report のクロスリンクと同じ組み立て)。技術ごとに author 見出し(技術名 + 求人言及数)・記事タイトル・「要約」フィールドを並べ、末尾にサイト一覧への導線リンクを添える。一度通知した記事は二度と載せない(通知済み guid 全件を除外)。

**有効化には Discord Webhook URL の設定が必須**。未設定なら notify feature の Bean は一切登録されず、他の機能に影響なく通常起動する(=無効化)。API キー未設定は「無効化」ではなく実行時フォールバックで、要約なし(技術見出し + 記事タイトル + リンクのみ)で投稿を続ける。

```bash
# 最小構成: Webhook URL を渡すと有効化される(要約を付けたい場合は API キーも)
export RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/xxxx/yyyy"
export ANTHROPIC_API_KEY="sk-ant-..."
java -jar rss-watch.jar
```

設定(いずれも省略可。`application.yml` の `rss-watch.notify` にデフォルトあり):

| 変数 / 設定キー | 意味 | デフォルト |
|---|---|---|
| `RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL` | Discord Webhook URL。**設定時のみ notify feature が有効**(未設定=無効) | (未設定) |
| `ANTHROPIC_API_KEY` | Claude API キー。未設定なら要約なしでフォールバック | (未設定) |
| `rss-watch.notify.cron` | 配信時刻(Spring cron 式) | `0 0 8 * * *`(毎朝 8:00) |
| `rss-watch.notify.window-days` | 求人で言及された技術の集計窓(日) | `7` |
| `rss-watch.notify.tech-limit` | 載せる技術の上位件数(求人言及数の多い順) | `3` |
| `rss-watch.notify.articles-per-tech` | 各技術に載せる関連記事の最大件数 | `3` |
| `rss-watch.notify.site-url` | 通知末尾に添えるサイト一覧への導線 URL | `https://rss-watch.rocky-ha.com/` |
| `rss-watch.notify.claude.model` | 要約モデル ID | `claude-haiku-4-5-20251001` |
| `rss-watch.notify.claude.max-tokens` | 要約の最大トークン数 | `256` |

> Webhook URL・API キーは機密情報。リポジトリにコミットせず、環境変数(systemd なら `Environment=` か `EnvironmentFile=`)で渡すこと。

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
デイリーダイジェスト(Discord 通知)実装完了(内訳は [discord-notifier/tasks.md](.spec-workflow/specs/discord-notifier/tasks.md))。
