# Requirements Document

> Source: [Issue #13](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/13)

## Introduction

「自分の今興味あること」を書いたファイル(`interests.toml`)を用意しておくと、蓄積済みの記事から興味に合うものをスコアリングして推薦する。Phase 1 は既存のキーワード抽出資産を活かした**辞書ベースのスコアリング**(LLM 不使用・説明可能)で実現し、LLM による自由記述の解釈・リランキングは Phase 2(将来 spec)に切り出す。

## Alignment with Product Vision

- product.md「既存の RSS リーダーは記事を時系列に並べるだけ」という課題への追加アプローチ。クロスリンク(市場軸)に対し、レコメンドは**自分の興味軸**で記事を並べ替える
- 「フィード追加は設定ファイルに行を足すだけ」と同じ思想で、興味も `interests.toml` に行を足すだけにする
- キーワード辞書ベース(説明可能性重視)という既存の設計判断と一貫させる

## Requirements

### Requirement 1: 興味定義ファイル

**User Story:** As a ユーザー, I want 興味をファイルに雑に書いておけること, so that 管理画面なしで興味の変化を反映できる

#### Acceptance Criteria

1. 興味は `interests.toml` に「キーワード + 重み(省略時 1.0)」のリストとして定義できること。キーワードは keywords feature の正規化名と同じ語彙を使う
2. WHEN `interests.toml` に行を追加した THEN システム SHALL 再起動のみで(コード変更なしで)新しい興味を反映する
3. IF ファイルが存在しない・空である THEN システム SHALL レコメンド機能を無効として扱い、他機能に影響を与えない
4. IF 辞書に存在しないキーワードが書かれていた THEN システム SHALL 起動時に警告ログを残す(タイポ検知)

### Requirement 2: スコアリングと推薦 API

**User Story:** As a ユーザー, I want 興味に合う記事が上位に並んだリストを見られること, so that 大量の蓄積記事から読むべきものをすぐ選べる

#### Acceptance Criteria

1. WHEN `GET /api/recommend?days=N` を呼んだ THEN API SHALL 直近 N 日の tech 記事を「興味キーワードとの一致度(重み合計)+ 新しさ」でスコアリングし、降順で返す
2. 各推薦結果には「なぜ推薦されたか」(一致した興味キーワードの一覧)を含めること(説明可能性)
3. IF 興味キーワードに 1 つも一致しない記事しかない THEN API SHALL 空リストを返す(無理に埋めない)
4. `days` の不正値(負数・非数値)には 400 を返すこと(既存 report API と同じ規約)

### Requirement 3: UI 表示

**User Story:** As a ユーザー, I want ブラウザの同じ画面でレコメンドも見られること, so that クロスリンクと合わせて 1 箇所で完結する

#### Acceptance Criteria

1. WHEN ブラウザで画面を開いた THEN UI SHALL 「おすすめ」セクションに推薦記事(スコア順)と一致キーワードを表示する
2. IF レコメンドが無効(interests.toml なし)または空 THEN UI SHALL セクションを非表示または「興味を設定してください」の案内にする

## Non-Functional Requirements

### Code Architecture and Modularity

- `recommend/` feature として追加する。archive のデータへは既存の `ArchiveQueryPort`(必要なら拡張)経由でアクセスし、直接 import しない
- スコアリングは純 Kotlin の domain に置く(Phase 2 で LLM リランキングに差し替え・併用できる境界にする)

### Performance

- 直近 N 日の記事(数百〜数千件)をメモリ上でスコアリングして十分。事前計算・キャッシュは作らない

### 将来拡張(Phase 2 のスコープ・本 spec には含めない)

- 自由記述(「最近 Rust と分散システムが気になる」等)を LLM で解釈して興味キーワードへ展開する
- LLM によるリランキング・推薦理由の自然文生成
