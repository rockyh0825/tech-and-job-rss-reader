# 外部公開(Cloudflare Tunnel + Access)

自宅サーバーの Web UI(クロスリンク表示 + SSE 新着)を、**自分と許可した数人だけ**がアクセスできる状態でインターネット公開する手順。**アプリ本体は無改造**で、認証・TLS 終端・アクセス制御はすべて Cloudflare エッジで行う。ルーターのポート開放・グローバル IP 公開・DDNS のいずれも不要(`cloudflared` が Cloudflare へ**アウトバウンド**でトンネルを張るため)。

```
ブラウザ(許可メールのみ)
   └─ HTTPS → Cloudflare エッジ(Access 認証ゲート + TLS 終端)
                └─ 認証通過のみ → Tunnel → cloudflared(自宅サーバー常駐)
                                              └─ http://localhost:8080 → rss-watch(無改造)
```

詳細な設計・要件は [private-web-access spec](../.spec-workflow/specs/private-web-access/) を参照。

## 前提(無料枠の条件)

- Cloudflare アカウントと Zero Trust(Free プラン)組織。ソフトウェアと Zero Trust Free は無料だが、**DNS を Cloudflare に向けたドメインが 1 つ必要**(ドメイン登録費用は別途。既存所有ドメインでも可)
- Free プランは **50 ユーザーまで無料**。それを超えると有料($7/user/月)。本ツールの想定(数人)は無料枠に収まる

## 手順

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

## 動作確認(手動 E2E)

公開後、以下を確認する。

- **許可アカウント**: 公開 URL → Access 認証 → クロスリンク UI 表示 → SSE 新着受信 まで通ること(`EventSource` は Cookie を自動送出するため、初回にブラウザで認証済みなら SSE も追加操作なしで通る)
- **許可外アカウント**: Access で拒否され、UI・API がオリジンに到達しないこと
- **ポリシー即時反映**: 許可メールを 1 件追加/削除し、アプリ再起動なしで反映されること
- **フェイルセーフ**: `cloudflared` を停止すると公開だけ止まり、LAN 内アクセス・パイプライン(fetch → Kafka → sink/live/notify)が継続すること

## 公開前チェックリスト

- [ ] トンネルトークンを**コミットしない**(`docker/.env` は `.gitignore` 済み。`git status` で追跡されていないことを確認)
- [ ] ルーターの**ポート開放・ポートフォワードをしない**(`cloudflared` のアウトバウンドのみ)
- [ ] アプリの `:8080` はインターネットに直接公開せず、唯一の経路をトンネル + Access に限定する
- [ ] Access Policy の**許可メールを最小限**にする(必要な数人のみ)
- [ ] グローバル IP を公開しない(DNS はトンネルの CNAME を指す)

> **注意**: Cloudflare Access を経由しない経路(自宅 LAN からの `:8080` 直アクセス)は認証されない。これは現状の信頼レベル(自宅 LAN 内)と同じで個人ツールとして許容する。より厳格にしたい場合は、下記「任意ハードニング」でオリジン側の JWT 検証を有効化する。

## 任意ハードニング: オリジンでの JWT 検証(防御多層化)

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
- `/actuator/health` と `/actuator/prometheus` の 2 パスだけは有効化しても 401 にならない(localhost の Prometheus コンテナが Access を経由せず scrape するため。詳細は [observability spec](../.spec-workflow/specs/observability/) 参照)

## Grafana の公開(観測ダッシュボード)

[Prometheus + Grafana の観測基盤](home-server.md)の Grafana(`127.0.0.1:3001`)を、rss-watch の UI と同じトンネル + Access で外部公開する手順。**新規トンネルは作成しない**(既存トンネルに Public Hostname を 1 つ足すだけ)。以下の手順 1〜2 はいずれも Cloudflare ダッシュボード操作のみで、**リポジトリ成果物はない**(ユーザー作業)。

**1. Grafana 用の Access アプリを追加**

Zero Trust ダッシュボード → **Access → Applications** で **Self-hosted** アプリを追加する。設定は rss-watch 用(手順 4)と同じ要領。

- **Application domain**: `grafana.example.com`(rss-watch とは別ホスト名)
- **Policy**: Action=Allow、Include=**Emails**(rss-watch と同じ許可メール)

> **順序に注意**: rss-watch の公開時(手順 4 の注意)と同じく、Access アプリを**先に**作成・有効化してから手順 2 で Public Hostname を追加すること。既存トンネルは稼働中のため、Public Hostname を足した時点で公開が live になる。

**2. 既存トンネルに Public Hostname を追加**

Zero Trust ダッシュボード → **Networks → Tunnels** → 既存トンネル(手順 2 で作成済み)の **Public Hostname** に以下を追加する。

| 項目 | 値 |
|---|---|
| Subdomain / Domain | `grafana.example.com` |
| Service | `http://localhost:3001` |

- Service の Type は **HTTP** にする(HTTPS を指定しない)。Grafana はローカルで平文 HTTP を話し、TLS 終端は Cloudflare エッジが行う
- cloudflared は `network_mode: host` で動いているため、ホストにループバック bind された `:3001` にそのまま到達する(compose 側の変更は不要)

**3. `docker/.env` に公開 URL を設定**

Grafana がリンク・リダイレクト URL を正しく組み立てられるよう、公開 URL を `GRAFANA_ROOT_URL` で注入して Grafana を再作成する(`docker/.env` は `.gitignore` 済み)。

```bash
# docker/.env に追記
GRAFANA_ROOT_URL=https://grafana.example.com
```

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml up -d grafana
```

公開後、`https://grafana.<ドメイン>` → Access 認証 → ダッシュボードが匿名(Viewer)で閲覧できることを確認する。編集は admin ログインが必要(admin パスワードの設定・リセットは [docs/home-server.md](home-server.md) を参照)。
