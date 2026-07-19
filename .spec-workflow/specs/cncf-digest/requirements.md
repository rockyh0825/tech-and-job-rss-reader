# Requirements Document

> Source: [Issue #46](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/46)

## Introduction

CNCF(Cloud Native Computing Foundation)関連の記事に特化したデイリーダイジェストを、既存のダイジェスト(求人技術 × 関連記事)とは**別の Discord チャンネル**(第 2 の Webhook URL)へ毎朝配信する。

Issue の動機は 2 つ:(1) CNCF の動向を Watch することでクラウドネイティブ領域の技術トレンドに強くなる、(2) graduated 前(sandbox / incubating)のプロジェクトを早期に掴み、トレンドの先取りや OSS コントリビュートの機会を増やす。この動機を実装に反映するため、単に記事を流すだけでなく、**記事中の CNCF プロジェクト言及を辞書で検出し、成熟度(graduated / incubating / sandbox)バッジを付与**し、**成熟度が低いプロジェクトに言及する記事を優先表示**する。

## Alignment with Product Vision

- product.md「技術トレンドの把握を日常のワークフローにする」の延長。CNCF 特化チャンネルを購読するだけでクラウドネイティブの動向が毎朝届く
- 既存パイプライン(fetch → Kafka → sink → DB)には手を入れず、`feeds.toml` へのフィード追加 + notify feature 内の追加クラスのみで実現する
- 既存ダイジェストの構成部品(要約・OGP サムネイル・投稿済み管理・Discord transport)を最大限再利用する

## Requirements

### Requirement 1: CNCF フィードの収集

**User Story:** As a ユーザー, I want CNCF 公式と Kubernetes 公式のブログ記事が自動収集されること, so that CNCF エコシステムの一次情報を見逃さない

#### Acceptance Criteria

1. `feeds.toml` に新カテゴリ `cncf` として CNCF Blog(`https://www.cncf.io/feed/`)と Kubernetes Blog(`https://kubernetes.io/feed.xml`)を登録できること
2. WHEN fetch スケジューラが実行された THEN 既存パイプラインと同じ経路(Rome パース → Kafka → sink → DB)で `category = "cncf"` の item として保存されること
3. `ItemCategory` に `CNCF("cncf")` を追加し、カテゴリの語彙は引き続き 1 箇所(`shared/contract/ItemCategory.kt`)で管理すること

### Requirement 2: CNCF デイリーダイジェストの配信

**User Story:** As a ユーザー, I want 毎朝 CNCF 関連の新着記事が専用チャンネルに届くこと, so that 既存ダイジェストと混ざらずに CNCF 動向だけを追える

#### Acceptance Criteria

1. WHEN スケジュール時刻(既定: 毎朝 8:10、cron 設定可能)になった THEN notifier SHALL 直近(既定: 過去 7 日)の `category = "cncf"` 記事から投稿済み guid を除外して候補を集める
2. 各記事は既存ダイジェストと同スタイル(AI 3 行要約 + OGP サムネイル + 記事ごと 1 embed)で投稿されること。要約・サムネイル取得の失敗時は既存と同じフォールバック(要約なし・画像なしで投稿継続)
3. 1 回の配信は最大 N 件(既定 8 件、設定可能)に制限されること(初回有効化時のバックログ氾濫防止)
4. IF 候補が 0 件だった THEN 投稿をスキップし、ログのみ残す
5. 投稿済み guid は既存の `notify_posted` テーブルで永続管理し、二重投稿を防ぐこと(カテゴリが排他のため既存ダイジェストとの相互干渉はない)

### Requirement 3: プロジェクト成熟度バッジと優先表示

**User Story:** As a ユーザー, I want 各記事にどの CNCF プロジェクトの話かと成熟度が表示され、graduated 前の話題が上に来ること, so that 早期プロジェクトのトレンドをいち早く掴める

#### Acceptance Criteria

1. WHEN 記事を整形する THEN notifier SHALL タイトル + 概要に対して CNCF プロジェクト辞書(graduated / incubating / sandbox の成熟度付き)でマッチングし、言及プロジェクトを検出する
2. embed の author 行に成熟度バッジを表示すること: `🌱 Sandbox: <名前>` / `🧪 Incubating: <名前>` / `🎓 Graduated: <名前>`(複数言及時は最も成熟度が低いプロジェクトのバッジ + 残り最大 2 件を併記)、言及なしは `☸️ CNCF`
3. 配信順は成熟度 tier 順(sandbox 言及 → incubating 言及 → graduated 言及 → 言及なし)、tier 内は新着順とすること(tier は記事の言及プロジェクトのうち最も低い成熟度で決める)
4. 辞書マッチングは単語境界を尊重し、一般名詞と衝突する名前(Harbor / Helm / Envoy 等)は大文字小文字を区別して誤検出を抑えること

### Requirement 4: 独立したフィーチャートグルと既存機能の無影響

**User Story:** As a 開発者, I want CNCF 配信を既存ダイジェストと独立に有効化/無効化できること, so that 片方だけ運用する構成でも安全に起動できる

#### Acceptance Criteria

1. IF `rss-watch.notify.cncf.discord-webhook-url` が未設定 THEN CNCF 配信の Bean は一切登録されず、アプリは通常起動すること
2. IF CNCF 側の Webhook URL のみ設定(既存側は未設定) THEN 共有部品(要約・サムネイル・投稿済み管理)は登録され、既存ダイジェスト固有の Bean(スケジューラ・ローテーション等)は登録されないこと
3. CNCF フィードの記事は既存ダイジェスト(`category = "tech"` フィルタ)の候補に**含まれない**こと
4. CNCF 配信の障害は既存ダイジェスト・fetch・sink・live に波及しないこと

## Non-Functional Requirements

### Code Architecture and Modularity

- `notify/` feature 内に追加し、他 feature への直接 import をしない(structure.md 準拠)。DB 読み取りは `capabilities/ArchiveQueryPort` の既存メソッド `itemsByCategory` を再利用する(Port にメソッドを追加しない)
- CNCF プロジェクト辞書・マッチャ・選定ルールは純 Kotlin の domain に置く。成熟度は通知専用の関心事のため keywords feature には置かない
- Discord transport(リトライ・レート制限・embed 分割)は既存クライアントから抽出して両チャンネルで共有する

### 受容する制限

- report UI / API(`/api/report`)は TECH / JOBS 固定のため CNCF 記事は表示されない(live SSE には表示される)。必要になったら別 issue で対応
- CNCF プロジェクト辞書は手書き管理(graduated ほぼ全件 + incubating・sandbox は厳選)。新プロジェクトの追加は 1 行追加で行う

### Security

- CNCF 用 Webhook URL は環境変数 `RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL` で渡し、リポジトリにコミットしない

### Performance / Cost

- LLM 呼び出しは 1 日あたり最大 N 件(既定 8)の追加のみ。既定モデルは既存と同じ Haiku 系
- スケジューリングプールを 3 に増やし、fetch / 既存ダイジェスト / CNCF ダイジェストが互いをブロックしないようにする(配信時刻も 8:00 / 8:10 とずらす)

### Reliability

- LLM・OGP・Discord の障害時もジョブが破綻しない(フォールバック + ログ + 翌日再実行)。部分投稿失敗時は投稿できた分のみ markPosted し、残りは翌日再挑戦
