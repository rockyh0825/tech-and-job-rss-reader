# 自宅サーバーでの常駐運用

fat jar を `/opt/rss-watch` に配置し、systemd(`rss-watch.service`)で常駐させる。依存(Kafka + PostgreSQL + Prometheus + Grafana)は Docker Compose、環境変数は `/etc/rss-watch.env` に集約する。

## 構成

| 役割 | 実体 |
|---|---|
| アプリ | `/opt/rss-watch/rss-watch.jar` を systemd `rss-watch.service` で常駐 |
| 設定・機密 | `/etc/rss-watch.env`(unit の `EnvironmentFile=` で読み込み) |
| Kafka + PostgreSQL + Prometheus + Grafana | `docker/docker-compose.yml`(`restart: unless-stopped` で再起動後も自動復帰) |
| デプロイ | main へのマージで GitHub Actions self-hosted runner が jar を差し替え(後述) |

## 初回セットアップ

```bash
# 1. fat jar をビルド
./gradlew bootJar   # → build/libs/rss-watch.jar

# 2. 配置
sudo mkdir -p /opt/rss-watch
sudo cp build/libs/rss-watch.jar /opt/rss-watch/rss-watch.jar
sudo cp feeds.toml /opt/rss-watch/

# 3. 依存サービス(Kafka + PostgreSQL + Prometheus + Grafana)を起動
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

## 観測(Prometheus + Grafana)

エンドポイント別レイテンシ(p50/p95/p99)・リクエストレート・JVM・HikariCP・Kafka リスナーを Grafana のダッシュボードで閲覧できる。Prometheus(収集・保持)と Grafana(可視化)は既存の `docker/docker-compose.yml` に含まれているため、専用の起動手順はない(初回セットアップ手順 3 の `up -d` で一緒に起動する)。

| サービス | ポート | 内容 |
|---|---|---|
| Prometheus | `127.0.0.1:9090`(ループバック限定) | ホスト上のアプリ(`:8080`)の `/actuator/prometheus` を 15 秒間隔で scrape し 90 日保持 |
| Grafana | `127.0.0.1:3001`(ループバック限定。自宅サーバーは homepage が `:3000` 使用中のため) | ダッシュボード。datasource・パネルは provisioning 済みで手動セットアップ不要 |

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
