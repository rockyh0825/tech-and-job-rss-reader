# デイリーダイジェスト(Discord 通知)

`notify` feature を有効にすると、毎朝 1 回「**記事で言及の多い注目技術**」の上位を選び、各技術に「**その技術の記事**」を添えて Claude 要約付きで Discord に投稿する(report のクロスリンクと同じ組み立て)。

このほかに [CNCF ダイジェスト](#cncf-ダイジェスト専用チャンネル)(CNCF フィードの新着を専用チャンネルへ配信)があり、既存ダイジェストとは**独立にオン/オフ**できる。

## 投稿の形式

投稿は **記事 1 件ごとに 1 通**に分ける。各通に author 見出し(技術名 + 記事言及数)・記事タイトル・「要約」フィールド・記事ページの OGP サムネイル(embed 右上)を載せ、**記事を投稿し終えたら最後にサイト一覧への導線リンクを 1 通**送る。一度通知した記事は二度と載せない(通知済み guid 全件を除外)。実際に投稿できた記事だけを通知済みとして記録するため、投稿済みの記事が翌日重複せず、未投稿の記事は次回の巡回で改めて候補に上がる。

サムネイルは記事ページを取得して `og:image`(無ければ `og:image:url` → `twitter:image`)を解決する。Discord は Webhook で渡した embed をそのまま描画しリンク先の OGP を自前で読みには行かないため、こちら側で解決する必要がある。取得・解析に失敗した記事は画像なしで投稿する。

## 失敗時の扱い

投稿の失敗は「その記事固有か / Discord へ到達できないか」で扱いを分ける。

| 種別 | 扱い |
|---|---|
| 429(レート制限) | `Retry-After` を尊重して `max-retries` 回までリトライ。使い切ったら打ち切り |
| 5xx / 接続失敗 | 1 秒待って `max-retries` 回までリトライ。使い切ったら打ち切り |
| 400 | **その記事だけスキップ**して次の記事へ進む(ペイロード固有の問題であり、他の記事とは無関係)。通知済みにはしないので翌日また試される |
| その他の 4xx(401/403/404 等) | 打ち切り(Webhook URL 自体が無効・削除済みで、以降も必ず失敗するため) |

導線リンクは「打ち切らずに最後まで投稿し終えた、かつ 1 件以上投稿できた」ときに送る(400 でスキップした記事があっても送る)。打ち切った場合は送らない。

## 有効化と設定

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
| `rss-watch.notify.window-days` | 記事で言及された技術の集計窓(日) | `7` |
| `rss-watch.notify.tech-limit` | 載せる技術の件数(候補プールから優先順位の高い順) | `3` |
| `rss-watch.notify.tech-pool-size` | 候補プールの足切り: 記事言及ランキングの上位何件までを候補にするか。「未紹介だから」という理由だけで言及の少ないマイナー技術が浮上するのを防ぐ。興味技術は足切りの対象外。1 以上(0 以下は起動エラー) | `10` |
| `rss-watch.notify.rotation-cooldown-days` | ローテーションのクールダウン(日)。直近 N 日以内に紹介した技術を後回しにし、クールダウンが明けた技術は未紹介の技術と同格で言及数を競う(ちょうど N 日前はクールダウン明け扱い)。1 以上(0 以下は起動エラー) | `3` |
| `rss-watch.notify.articles-per-tech` | 各技術に載せる関連記事の最大件数 | `3` |
| `rss-watch.notify.interests.categories` | 興味のある技術カテゴリ(`TechCategory` の値。例 `cloud-infra`)。該当技術を優先して選抜する | `[]` |
| `rss-watch.notify.interests.keywords` | 興味のある個別キーワード(辞書の正規化名。大文字小文字は無視。例 `Kotlin`)。興味技術を優先的に紹介する(記事ベースのランキングでは新着記事のある技術は常にランキング内のため、実質は優先度ブースト。ランキング軸を求人に戻した場合は圏外でも候補に含める)。`tech-pool-size` の足切り対象外 | `[]` |
| `rss-watch.notify.site-url` | 通知末尾に添えるサイト一覧への導線 URL | `https://rss-watch.rocky-ha.com/` |
| `rss-watch.notify.claude.model` | 要約モデル ID | `claude-haiku-4-5-20251001` |
| `rss-watch.notify.claude.max-tokens` | 要約の最大トークン数 | `256` |
| `rss-watch.notify.discord.max-retries` | 1 通あたりのリトライ上限(429 / 5xx / 接続失敗)。回数は**1 通ごとに数え直す** | `2` |
| `rss-watch.notify.ogp.timeout-ms` | サムネイル解決で記事ページを取得する際の、**1 リクエストあたり**のタイムアウト | `5000` |
| `rss-watch.notify.ogp.max-body-bytes` | 同上の本文サイズ上限(超過分は読まない) | `1048576`(1MiB) |

- 設定キーは環境変数でも渡せる(Spring の relaxed binding)。例: `rss-watch.notify.cron` → `RSS_WATCH_NOTIFY_CRON`
- cron は Spring の **6 フィールド形式「秒 分 時 日 月 曜日」**(Linux crontab の 5 フィールドと異なる)。例: `0 0 8 * * *` = 毎朝 8:00、`0 * * * * *` = 毎分。環境変数で渡す場合は値にスペースを含むためクォートすること
- 自宅サーバー(systemd)では `/etc/rss-watch.env` に設定する([docs/home-server.md](home-server.md) 参照)

> `ogp.timeout-ms` は**1 リクエストあたり**の上限で、記事 1 件あたりの上限ではない。jsoup はリダイレクトのホップごとにタイムアウトを取り直すため、リダイレクトが挟まると 1 記事の解決にかかる時間は最悪で `timeout-ms × 20`(jsoup のリダイレクト上限)まで伸び得る。

> Webhook URL・API キーは機密情報。リポジトリにコミットせず、環境変数(systemd なら `EnvironmentFile=`)で渡すこと。

## CNCF ダイジェスト(専用チャンネル)

CNCF 関連フィード(`feeds.toml` の `category = "cncf"`。CNCF Blog と Kubernetes Blog)の新着記事を、毎朝**別の Discord チャンネル**へ配信する([issue #46](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/46))。既存ダイジェストとは**独立にオン/オフ**でき、片方だけの運用もできる。

各記事の author 見出しには、記事中で言及された CNCF プロジェクトの**成熟度バッジ**が付く(🌱 Sandbox / 🧪 Incubating / 🎓 Graduated。言及なしは ☸️ CNCF)。**成熟度の低いプロジェクトに言及する記事ほど先に**並ぶ(graduated 前のプロジェクトを早期に掴む、という issue の動機の反映)。要約・サムネイル・重複防止・失敗時の扱いは既存ダイジェストと同じ。

```bash
# CNCF 用 Webhook URL を渡すと有効化される(既存側とは別のチャンネルの Webhook を作って渡す)
export RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/xxxx/zzzz"
```

| 変数 / 設定キー | 意味 | デフォルト |
|---|---|---|
| `RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL` | CNCF 用 Discord Webhook URL。**設定時のみ CNCF 配信が有効**(未設定=無効) | (未設定) |
| `rss-watch.notify.cncf.cron` | 配信時刻(Spring cron 式)。既存ダイジェストと 10 分ずらしてある | `0 10 8 * * *`(毎朝 8:10) |
| `rss-watch.notify.cncf.window-days` | 候補記事の取得窓(日) | `7` |
| `rss-watch.notify.cncf.max-articles` | 1 回の配信に載せる記事の上限(初回のバックログ氾濫防止) | `8` |
| `rss-watch.notify.cncf.cta-url` | 通知末尾に添える導線 URL | `https://www.cncf.io/projects/` |

> プロジェクト成熟度の辞書は `keywords/domain/CncfProjects.kt` の手書き管理(graduated はほぼ全件、incubating / sandbox は厳選)。CNCF 側で昇格・アーカイブがあったら行を移す。CNCF カテゴリの記事は既存ダイジェストや `/api/report` には混ざらず、この専用チャンネルと Web UI の CNCF タブ(`GET /api/cncf`。Webhook 未設定でも常に有効)に流れる。

## フィードバック回収(リアクション・返信)

ダイジェスト投稿への**リアクション(絵文字)と返信**を定期回収して DB に貯める([issue #72](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/72))。将来のレコメンド改善([issue #70](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/70) Step 4)の学習データ集めが目的で、**この段階では収集のみ**(GOOD/BAD などの解釈や選定への反映はしない。解釈は貯まったデータを見てから決める)。

### 有効化(Bot が必要)

Webhook は送信専用でリアクションを読めないため、読み取りには **Discord Bot** が要る:

1. [Discord Developer Portal](https://discord.com/developers/applications) で Application を作成し、Bot タブでトークンを発行する
2. 返信の**本文**まで貯めたい場合は、同じ Bot タブで **Message Content Intent** を ON にする(OFF でも「返信が付いた事実」は取れるが、本文は空文字で保存される)
3. OAuth2 → URL Generator で scope `bot`、権限 **View Channel + Read Message History** の招待 URL を作り、ダイジェストを配信しているサーバーへ Bot を招待する(投稿は引き続き Webhook なので送信系の権限は不要)
4. トークンを環境変数で渡す:

```bash
export RSS_WATCH_NOTIFY_FEEDBACK_BOT_TOKEN="..."
```

トークン未設定なら回収一式は無効のまま(投稿側の message ID 記録だけが走る)。トークンだけ設定してもダイジェスト(Webhook)が無効なら回収対象が無いので有効にならない。

| 変数 / 設定キー | 意味 | デフォルト |
|---|---|---|
| `RSS_WATCH_NOTIFY_FEEDBACK_BOT_TOKEN` | Discord Bot トークン。**設定時のみ回収が有効**(未設定=無効) | (未設定) |
| `rss-watch.notify.feedback.cron` | 回収の実行時刻(Spring cron 式) | `0 30 */6 * * *`(6 時間おき) |
| `rss-watch.notify.feedback.lookback-days` | 回収対象: 直近 N 日に投稿したメッセージ | `7` |

### リアクションや返信でどうデータが集まるか

ダイジェスト投稿時に Webhook へ `?wait=true` を付けて、**記事 guid ↔ Discord メッセージ ID** の対応を `notify_discord_message` に記録しておく(この記録は Bot トークンなしでも動く)。回収ジョブ(既定 6 時間おき)は直近 `lookback-days` 日のメッセージについて:

| あなたの操作 | 集まるデータ | 保存先 |
|---|---|---|
| 投稿に 👍 や 👎 などの絵文字を押す | メッセージ × 絵文字ごとの**件数**(取得時点のスナップショット) | `notify_reaction` |
| 絵文字を取り消す | 次回回収時のスナップショットから消える(=取り消しも反映される) | `notify_reaction` |
| 投稿に**返信機能**で返信する(「あとで見る」等) | 返信 1 件ごとに、どの記事への返信か・誰が・いつ・**本文**(Intent ON のとき) | `notify_reply` |
| 返信の本文を編集する | 次回回収時に本文が上書きされる | `notify_reply` |

記事情報(タイトル・キーワード等)とは `notify_discord_message.guid` → `items.guid` で結合できるので、「どの技術の記事に 👍 が付きやすいか」「どんな記事に『あとで見る』が付くか」を後から SQL で集計できる。

**拾えないもの・注意**:

- **手打ちの引用(`> ...`)や、返信機能を使わないただの発言**は拾えない(Discord の `message_reference` が付かないため)。フィードバックのつもりの返信は必ず「メッセージを右クリック → 返信」で
- 返信は Discord API の**チャンネル直近 100 通**からしか探せない。チャンネルの流量が多いと、回収間隔(6 時間)の間に流れた返信は取りこぼし得る
- リアクションは**件数のみ**で「誰が押したか」は貯めない(実質 1 人運用のため。必要になったら拡張)
- **返信の削除には追従しない**(本文の編集は上書きで追従するが、Discord 側で削除した返信も DB には残る。リアクションのスナップショット方式と違う点に注意)
- 投稿が削除されたメッセージは回収をスキップする(最後のスナップショットが残る)
- Bot 導入前に投稿されたダイジェストは message ID の記録が無いため回収対象外(`?wait=true` 導入後の投稿から貯まり始める)

集計の例(「👍 が付いた記事のキーワード」):

```sql
SELECT i.title, r.emoji, r.reaction_count
FROM notify_reaction r
JOIN notify_discord_message m ON m.message_id = r.message_id
JOIN items i ON i.guid = m.guid
ORDER BY r.updated_at DESC;
```
