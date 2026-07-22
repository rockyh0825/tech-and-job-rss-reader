# 自宅サーバーでの常駐運用

fat jar を `/opt/rss-watch` に配置し、systemd(`rss-watch.service`)で常駐させる。依存(Kafka + PostgreSQL + Prometheus + Grafana + Tempo)は Docker Compose、環境変数は `/etc/rss-watch.env` に集約する。

## 構成

| 役割 | 実体 |
|---|---|
| アプリ | `/opt/rss-watch/rss-watch.jar` を systemd `rss-watch.service` で常駐 |
| 設定・機密 | `/etc/rss-watch.env`(unit の `EnvironmentFile=` で読み込み) |
| Kafka + PostgreSQL + Prometheus + Grafana + Tempo | `docker/docker-compose.yml`(`restart: unless-stopped` で再起動後も自動復帰) |
| デプロイ | main へのマージで GitHub Actions self-hosted runner が jar を差し替え(後述) |

## 初回セットアップ

```bash
# 1. fat jar をビルド
./gradlew bootJar   # → build/libs/rss-watch.jar

# 2. 配置
sudo mkdir -p /opt/rss-watch
sudo cp build/libs/rss-watch.jar /opt/rss-watch/rss-watch.jar
sudo cp feeds.toml /opt/rss-watch/

# 3. 依存サービス(Kafka + PostgreSQL + Prometheus + Grafana + Tempo)を起動
#    注意: Grafana の admin パスワードは初回 up -d 前に docker/.env で用意する(後述「観測」参照)
docker compose -f docker/docker-compose.yml up -d
```

### `/etc/rss-watch.env`(環境変数の集約先)

機密(Webhook URL・API キー)を含むため、unit に直書きせず env ファイルに集約し、root のみ読めるようにする。

```bash
sudo touch /etc/rss-watch.env
sudo chmod 600 /etc/rss-watch.env
```

```bash
# /etc/rss-watch.env の例(すべて省略可。デフォルトは compose のローカル値)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
RSS_WATCH_DB_URL=jdbc:postgresql://localhost:5432/rsswatch
RSS_WATCH_DB_USER=rsswatch
RSS_WATCH_DB_PASSWORD=rsswatch

# デイリーダイジェスト(Discord 通知)。Webhook URL を設定したときだけ有効(詳細は docs/notify.md)
RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/xxxx/yyyy
ANTHROPIC_API_KEY=sk-ant-...

# CNCF ダイジェストも使う場合(専用チャンネルの Webhook。詳細は docs/notify.md)
RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/xxxx/zzzz

# 配信時刻を変える場合(Spring cron 6 フィールド「秒 分 時 日 月 曜日」)
RSS_WATCH_NOTIFY_CRON="0 0 8 * * *"
```

- スペースを含む値(cron 式)はクォートしておくと安全(`EnvironmentFile` はクォートなしでもスペースを保持するが、unit への `Environment=` 直書きや shell の `export` に転用するとクォート必須になるため)
- 編集後は `sudo systemctl restart rss-watch` で反映する(`EnvironmentFile` は起動時にしか読まれない)
- プロセスが実際に受け取った値の確認: `sudo cat /proc/$(systemctl show -p MainPID --value rss-watch)/environ | tr '\0' '\n'`(`systemctl show -p Environment` に出るのは `Environment=` 直書き分のみで、`EnvironmentFile` の値は表示されない)

### systemd unit

`/etc/systemd/system/rss-watch.service`:

```ini
[Unit]
Description=RSS Watch (tech-and-job-rss-reader)
# Kafka(docker)より後に起動させる。落ちても Restart=always で拾えるので依存は緩めでよい
After=network-online.target docker.service
Wants=network-online.target

[Service]
# root で実行しない(自動デプロイで jar を書き込むユーザーと同じにする。
# root 実行だと jar を書き換えられること = root 権限奪取になってしまう)
User=rocky
WorkingDirectory=/opt/rss-watch
EnvironmentFile=/etc/rss-watch.env
ExecStart=/usr/bin/java -jar /opt/rss-watch/rss-watch.jar
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

## 観測(Prometheus + Grafana + Tempo)

エンドポイント別レイテンシ(p50/p95/p99)・リクエストレート・JVM・HikariCP・Kafka リスナーを Grafana のダッシュボードで、リクエスト単位の内訳(トレース)を Grafana の Explore で閲覧できる。Prometheus(メトリクスの収集・保持)・Grafana(可視化)・Tempo(トレースの収集・保持)は既存の `docker/docker-compose.yml` に含まれているため、専用の起動手順はない(初回セットアップ手順 3 の `up -d` で一緒に起動する)。

| サービス | ポート | 内容 |
|---|---|---|
| Prometheus | `127.0.0.1:9090`(ループバック限定) | ホスト上のアプリ(`:8080`)の `/actuator/prometheus` を 15 秒間隔で scrape し 90 日保持 |
| Grafana | `127.0.0.1:3001`(ループバック限定。自宅サーバーは homepage が `:3000` 使用中のため) | ダッシュボード + トレース閲覧(Explore)。datasource・パネルは provisioning 済みで手動セットアップ不要 |
| Tempo | `127.0.0.1:4318`(ループバック限定) | ホスト上のアプリが OTLP/HTTP で push するトレースを受信し 14 日保持(後述「分散トレーシング」) |

- 閲覧は匿名(Viewer)でログイン不要。ダッシュボードの編集だけ admin ログインが必要
- 外部公開(`https://grafana.<ドメイン>`)の手順は [docs/public-access.md](public-access.md) を参照

### 初回 `up -d` の前に `docker/.env` を用意する

Grafana の admin パスワード(`GF_SECURITY_ADMIN_PASSWORD`)は **grafana-data volume の初回初期化時にのみ**反映され、あとから `docker/.env` を変えて再起動しても変わらない。**初回の `up -d` より前に** `docker/.env` を用意しておくこと(`docker/.env.example` 参照。コミットしない)。

```bash
# docker/.env
GRAFANA_ADMIN_PASSWORD=<admin パスワード>
GRAFANA_ROOT_URL=https://grafana.example.com   # Tunnel で公開する場合のみ。ローカルは未設定でよい(既定 http://localhost:3001)
```

初回以降にパスワードを変更する場合は、コンテナ内でリセットする(新形式の `grafana cli` を使う。旧形式の `grafana-cli` は同コマンドへの deprecated ラッパー):

```bash
docker compose -f docker/docker-compose.yml exec grafana grafana cli admin reset-admin-password <新パスワード>
```

### Prometheus target の確認とトラブルシュート

デプロイ後、`http://localhost:9090/targets` で `rss-watch` target が **UP** になっていることを確認する(ループバック限定 bind のため、サーバー上で確認するか SSH ポートフォワードで開く)。

- **DOWN の場合は、まずホスト側 firewall(ufw 等)を疑う**。Prometheus はコンテナから `host.docker.internal:8080`(= ホストの `:8080`)へ scrape するため、firewall がコンテナ → ホストの `:8080` を遮断していると target が DOWN になる(その場合は Docker ブリッジからの `:8080` 受信を許可する)
- アプリ停止中も DOWN になるが、Prometheus 側の対処は不要(アプリ再起動後に自動復帰する。欠けるのは停止期間のメトリクスのみ)

### 分散トレーシング(Tempo)

メトリクスで「`/api/report` が遅い」ことは分かっても内訳は分からない。トレースを見ると、1 リクエストの中で「どの SQL に何 ms かかったか」がスパンのウォーターフォール図で読み取れる。アプリ(Micrometer Tracing + OTLP)がトレースを push し、Tempo が受信・保存する。閲覧は既存 Grafana に集約し、専用 UI は増やさない。

| 項目 | 内容 |
|---|---|
| 受信ポート | OTLP/HTTP `127.0.0.1:4318`(ループバック限定 bind。ホスト上のアプリから届けばよく LAN に露出しない) |
| Tempo API | `:3200` はホストへ publish しない(Grafana から compose 内 DNS `tempo:3200` で参照する) |
| 保持期間 | 14 日(`block_retention: 336h`。用途が直近の性能調査なので Prometheus の 90 日は不要)。データは named volume `tempo-data` に永続化 |
| 設定 | `docker/tempo/tempo.yml`(コミット対象) |

#### 閲覧手順(Grafana Explore + TraceQL)

1. Grafana(`http://localhost:3001`)左メニューの **Explore** を開き、datasource に **Tempo** を選ぶ(provisioning 済み)
2. **TraceQL** タブにクエリを入れて実行し、ヒットした Trace ID を開くとウォーターフォール図が表示される

TraceQL の例(いずれも実際にヒットすることを確認済み):

```
# このアプリの全トレース(service name は spring.application.name)
{resource.service.name="tech-and-job-rss-reader"}

# /api/report のトレース(HTTP サーバースパンの名前は「メソッド + パス」の小文字)
{name="http get /api/report"}

# 遅かったリクエストに絞る(全量サンプリングなので外れ値のトレースも必ず残っている)
{resource.service.name="tech-and-job-rss-reader" && span:duration > 500ms}
```

#### ウォーターフォールの読み方

- ルートは HTTP サーバースパン(例: `http get /api/report`。`uri`・`method`・`status` タグ付き)
- その子に JDBC のスパンが並ぶ: `connection`(コネクション取得)→ `query`(SQL 実行。属性 `jdbc.query[0]` に SQL 文が入る。バインドパラメータ値は入らない)→ `result-set`(結果の読み出し)
- **同型の `query` スパンの繰り返し**が見えたら N+1 のサイン(#59 で解消した形の再発を目視で検出できる)
- **最後の SQL 完了から HTTP スパン終了までの差分**が集計・シリアライズ・レスポンス書き出しの時間(専用スパンはないが差分で読める)

#### Tempo 停止時の挙動

Tempo が止まっていてもアプリは無影響(ローカルで compose を上げずに `bootRun` する場合も同じ)。送信はバックグラウンドの非同期バッチのため、リクエスト処理は継続し、失敗したスパンは破棄され、ERROR ログ(`HttpExporter : Failed to export spans. ... Failed to connect to /127.0.0.1:4318`)がバッチ送信間隔でスロットリングされて出るだけ。compose を上げれば解消する。恒常的に Tempo なしで動かす環境では `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false` で送信だけ止められる。

#### スコープ外(将来課題)

- **exemplars**(Grafana のメトリクスのグラフ点からトレースへ直接ジャンプする連携): 設定が複雑になる割に、自宅規模では Explore の TraceQL 検索で足りるため見送り
- **Kafka のトレース連結**(producer → consumer のヘッダ伝播で fetch → sink/live を 1 トレースに繋ぐ): sink(バッチリスナー)は spring-kafka の observation 非対応で、live のみ有効化しても既存 Grafana「Kafka リスナー」パネルのタグ体系が変わるデグレ対処が必要になるため見送り(distributed-tracing spec の Task 3 参照)

## 自動デプロイ(GitHub Actions self-hosted runner)

main へ push(PR マージ)されると、GitHub ホストの runner でビルド・テストした jar を、自宅サーバー常駐の self-hosted runner が受け取って `/opt/rss-watch/` に配置し、`rss-watch.service` を再起動する(`.github/workflows/ci.yml` の `deploy` ジョブ)。runner は GitHub へ**アウトバウンド**で long-poll するだけなので、ポート開放は不要。

### runner の初回セットアップ

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

> `feeds.toml` もデプロイのたびにリポジトリの内容で上書きされる。フィードの追加・変更はサーバー上で直接編集せず、リポジトリ側を変更して main にマージすること。

### ロールバック

デプロイ時に直前の jar が `/opt/rss-watch/rss-watch.jar.prev` として残る。新しい jar で起動に失敗した場合は手動で戻す:

```bash
cp -p /opt/rss-watch/rss-watch.jar.prev /opt/rss-watch/rss-watch.jar
sudo systemctl restart rss-watch
```
