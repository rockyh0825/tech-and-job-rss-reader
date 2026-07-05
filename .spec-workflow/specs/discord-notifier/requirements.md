# Requirements Document

> Source: [Issue #10](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/10)

## Introduction

収集した技術記事のうち「読む価値がありそうなもの」を選別し、AI による 3 行要約を添えて Discord チャンネルへ自動投稿する。topic `rss.items` を新しい consumer group(`notify`)で購読する独立 feature として追加し、既存 feature には変更を加えない。

## Alignment with Product Vision

- product.md「技術トレンドの把握を日常のワークフローにする」の延長。ブラウザを開かなくても Discord に流れてくる
- structure.md「マイクロサービスへの発展方針」に明記された `discord-notifier` の具体化。**3 つ目の consumer group** を追加することで、consumer group の独立性という学習テーマを深める

## Requirements

### Requirement 1: 通知対象の選別

**User Story:** As a ユーザー, I want 全記事ではなく「いい感じの記事」だけが流れてくること, so that Discord チャンネルがスパム化しない

#### Acceptance Criteria

1. WHEN `rss.items` からメッセージを受信した THEN notifier SHALL `category = "tech"` かつキーワードが 1 件以上抽出されている記事のみを通知候補とする
2. IF 同じ `guid` の記事が再配信された THEN notifier SHALL 二重投稿しない(投稿済み guid を記録する)
3. WHEN 通知候補が短時間に集中した THEN notifier SHALL レート制限(設定可能な間隔・件数上限)の範囲内でのみ投稿する

### Requirement 2: AI 3 行要約

**User Story:** As a ユーザー, I want 記事の内容が 3 行で分かること, so that リンクを開くかどうかを Discord 上で判断できる

#### Acceptance Criteria

1. WHEN 通知対象の記事を投稿する THEN notifier SHALL タイトル + 概要を LLM(Claude API)に渡し、日本語 3 行の要約を生成する
2. IF LLM 呼び出しが失敗した(タイムアウト・レート制限・API キー未設定) THEN notifier SHALL 要約なし(タイトル + リンク + キーワードのみ)で投稿にフォールバックし、パイプラインを止めない
3. 要約プロンプトと最大トークン数は設定で調整可能であること

### Requirement 3: Discord への投稿

**User Story:** As a ユーザー, I want 記事が見やすい形式で Discord チャンネルに届くこと, so that スマホからでも流し読みできる

#### Acceptance Criteria

1. WHEN 投稿する THEN notifier SHALL Discord Webhook(URL は環境変数)に、タイトル・リンク・3 行要約・抽出キーワードを含む embed 形式で POST する
2. IF Webhook への POST が失敗した THEN notifier SHALL リトライ(上限あり)し、最終的に失敗した場合はログを残して次のメッセージへ進む
3. IF Webhook URL が未設定 THEN notifier SHALL 機能を無効化して起動し、他 feature の動作に影響を与えない

### Requirement 4: consumer group の独立性

**User Story:** As a 開発者, I want notifier の障害・停止が既存パイプラインに波及しないこと, so that 安心して後段処理を追加できる

#### Acceptance Criteria

1. notifier は groupId = `notify` の独立した consumer group として `rss.items` を購読すること
2. WHEN notifier を停止・再起動した THEN sink / live SHALL 影響を受けずに動作を継続する
3. WHEN notifier を再起動した THEN notifier SHALL 自分のオフセットから再開する(投稿済み guid 記録により二重投稿はしない)

## Non-Functional Requirements

### Code Architecture and Modularity

- `notify/` feature として追加し、他 feature への直接 import をしない(structure.md 準拠)
- LLM クライアント・Discord クライアントは infrastructure に閉じ込め、選別ルールは純 Kotlin の domain に置く

### Security

- Claude API キー・Webhook URL は環境変数で渡し、リポジトリにコミットしない

### Performance / Cost

- LLM 呼び出しは通知対象のみ(全メッセージではない)。1 日数十件想定で API コストを許容範囲に保つ

### Reliability

- LLM・Discord の障害時もオフセット処理が破綻しない(フォールバック + ログ)
