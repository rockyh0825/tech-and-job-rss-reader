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
| `rss-watch.notify.tech-pool-size` | 候補プールの足切り: 記事言及ランキングの上位何件までを候補にするか。「未紹介だから」という理由だけで言及の少ないマイナー技術が浮上するのを防ぐ。興味技術は足切りの対象外 | `10` |
| `rss-watch.notify.rotation-cooldown-days` | ローテーションのクールダウン(日)。直近 N 日以内に紹介した技術を後回しにし、クールダウンが明けた技術は未紹介の技術と同格で言及数を競う(ちょうど N 日前はクールダウン明け扱い) | `3` |
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
