# Requirements Document

> Source: [Issue #10](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/10)

## Introduction

収集した技術記事のうち「読む価値がありそうなもの」を **1 日 1 回・朝にまとめて**、AI による 3 行要約を添えて Discord チャンネルへ自動投稿する(デイリーダイジェスト)。

当初案の「`rss.items` を新 consumer group で購読し、良い記事が来るたびリアルタイム投稿する」方式は、ユーザーの求める体験(毎朝おすすめ数件だけ・全部は流さない)と一致せず、初回起動時にバックログを一斉投稿してしまう問題があるため採用しない。代わりに **`@Scheduled` の定期ジョブが PostgreSQL(sink が蓄積した当日分)を集計 → 上位 N 件を選抜 → 要約 → 投稿** する構成にする。これによりレート制限・初回氾濫・二重投稿の懸念がまとめて解消する。

## Alignment with Product Vision

- product.md「技術トレンドの把握を日常のワークフローにする」の延長。ブラウザを開かなくても、毎朝 Discord に "今日のおすすめ" が届く
- fetcher と同じ `@Scheduled` を後段にもう 1 つ足すだけで、既存パイプライン(fetch → Kafka → sink → DB)には手を入れない(archive の読み取り Port を 1 つ拡張するのみ)
- 対象は当面 **tech 記事のみ**。日本語求人が柔軟に扱えるようになった段階で jobs 対応を将来課題とする([[rss-watch-direction]])

## Requirements

### Requirement 1: デイリーダイジェストの選抜

**User Story:** As a ユーザー, I want 毎朝、当日分の中から "おすすめ" の数件だけが届くこと, so that 全記事に埋もれずに良い記事だけ拾える

#### Acceptance Criteria

1. WHEN スケジュール時刻(既定: 毎朝 8:00、cron 設定可能)になった THEN notifier SHALL 直近の対象記事(既定: 過去 24 時間、`category = "tech"`)を DB から取得する
2. WHEN 候補を選抜する THEN notifier SHALL 「人気フィード由来を優先」する選別ルールで上位 N 件(既定 5 件)に絞る
3. IF 過去のダイジェストで既に投稿済みの `guid` があった THEN notifier SHALL それを候補から除外する(日跨ぎの重複防止)
4. IF 当日分の候補が 0 件だった THEN notifier SHALL 投稿をスキップし、ログのみ残す

### Requirement 2: AI 3 行要約

**User Story:** As a ユーザー, I want 各記事が 3 行で分かること, so that リンクを開くか Discord 上で判断できる

#### Acceptance Criteria

1. WHEN ダイジェストに載せる記事を整形する THEN notifier SHALL タイトル + 概要を Claude API(Haiku 系。既定モデル ID は設定値)に渡し、日本語 3 行の要約を生成する
2. IF LLM 呼び出しが失敗した(タイムアウト・レート制限・API キー未設定) THEN notifier SHALL 要約なし(タイトル + リンク + キーワードのみ)でフォールバックし、ダイジェスト全体を止めない
3. 要約モデル・プロンプト・最大トークン数は設定で調整可能であること

### Requirement 3: Discord への投稿

**User Story:** As a ユーザー, I want ダイジェストが見やすい形式で 1 通届くこと, so that スマホからでも流し読みできる

#### Acceptance Criteria

1. WHEN 投稿する THEN notifier SHALL Discord Webhook(URL は環境変数)に、N 件を embed 形式(各: タイトル・リンク・3 行要約・キーワード)でまとめて POST する(1 通に複数 embed。Discord の上限 10 embed/通の範囲)
2. IF Webhook への POST が失敗した THEN notifier SHALL リトライ(上限あり)し、最終的に失敗した場合はログを残す(オフセット等はなく、翌日のジョブで自然に再挑戦)
3. IF Webhook URL が未設定 THEN notifier SHALL 機能を無効化して起動し、他 feature の動作に影響を与えない

### Requirement 4: 既存パイプラインの無影響

**User Story:** As a 開発者, I want notifier の障害・停止が既存パイプラインに波及しないこと, so that 安心して後段処理を追加できる

#### Acceptance Criteria

1. notifier は DB の**読み取り**(archive の Query Port 経由)+ 外部 API 呼び出しのみを行い、fetch / sink / live の動作に影響しないこと
2. WHEN notifier のジョブが失敗・例外終了した THEN 次回スケジュールで通常どおり再実行され、パイプライン本体は影響を受けないこと
3. WHEN Webhook URL 未設定で起動した THEN notifier の Bean は登録されず、アプリは通常起動すること

## Non-Functional Requirements

### Code Architecture and Modularity

- `notify/` feature として追加し、他 feature への直接 import をしない(structure.md 準拠)。DB 読み取りは `capabilities/` の Query Port 経由(archive の実装を直接参照しない)
- LLM クライアント・Discord クライアント・投稿済みリポジトリは infrastructure に閉じ込め、選抜ルールは純 Kotlin の domain に置く

### Security

- Claude API キー(`ANTHROPIC_API_KEY`)・Webhook URL は環境変数で渡し、リポジトリにコミットしない

### Performance / Cost

- LLM 呼び出しは 1 日あたり最大 N 件(既定 5)のみ。Haiku 系モデルを既定とし、月間コストを極小に保つ
- Discord は 1 日 1 通のため、Webhook のレート上限(概ね 30 通/分)には実質かからない

### Reliability

- LLM・Discord の障害時もジョブが破綻しない(フォールバック + ログ + 翌日再実行)
