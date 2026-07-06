# Design Document

## Overview

`recommend/` feature を新設する。`interests.toml`(興味キーワード + 重み)を起動時に読み込み、`GET /api/recommend?days=N` で直近 N 日の tech 記事を「興味一致度 + 新しさ」でスコアリングして返す。データ取得は archive の既存 Port 経由、スコアリングは純 Kotlin の domain。LLM は使わない(Phase 2 で差し込める境界だけ用意する)。

## Steering Document Alignment

### Technical Standards (tech.md)

- 「キーワード抽出は辞書ベース(透明性と保守の容易さ)」の決定をレコメンドにも延長する。スコアの根拠(一致キーワード)を必ず返す
- 蓄積は DB・集計は SQL + Kotlin という既存の役割分担を守る

### Project Structure (structure.md)

```
recommend/
├── presentation/     # RecommendController(GET /api/recommend)
├── application/      # BuildRecommendationsUseCase
├── domain/           # Interest・InterestScorer(スコアリングルール。純 Kotlin)
└── infrastructure/   # InterestConfigLoader(interests.toml)
```

- archive への依存は `capabilities/ArchiveQueryPort` 経由(report と同じパターン)。既存メソッド `itemsByCategory("tech", days)` 相当で足りるが、不足があれば Port に後方互換で追加する
- 興味キーワードの語彙検証(要件 1.4)には `capabilities/KeywordExtractionPort` に正規化名一覧の取得を追加する(keywords feature が実装)

## Code Reuse Analysis

- **FeedConfigLoader(fetch/infrastructure)**: TOML 読み込みの実装パターン(パース・必須項目検証・エラー処理)を InterestConfigLoader に流用する
- **ArchiveQueryPort / ArchiveQueryPortImpl**: report と同じ経路で記事を取得。SQL 資産を再利用
- **keywords の正規化名辞書**: interests.toml の語彙 = 抽出キーワードの語彙。一致判定が文字列比較だけで済む

## Architecture

```mermaid
flowchart LR
    B["ブラウザ(おすすめセクション)"] --> C["RecommendController<br/>(presentation)"]
    C --> U["BuildRecommendationsUseCase<br/>(application)"]
    U --> S["InterestScorer<br/>(domain: 純 Kotlin)"]
    U -->|ArchiveQueryPort| A["archive feature"]
    L["InterestConfigLoader<br/>(infrastructure: interests.toml)"] --> U
    L -.語彙検証.->|KeywordExtractionPort| K["keywords feature"]
```

## Components and Interfaces

### Interest / InterestScorer(domain)

- **Purpose:** 興味定義(キーワード + 重み)と、記事のスコアリングルール。純 Kotlin
- **Interfaces:** `score(itemKeywords: Set<String>, publishedAt: Instant?, now: Instant): Score`
- **スコア式(Phase 1):** `一致した興味の重み合計 × 新しさ係数`。新しさ係数は線形減衰(days 窓の先頭 = 1.0、末尾 = 0.5)から始める。定数は domain 内に明示し、テストで振る舞いを固定する
- **Score:** 数値 + 一致キーワード一覧(説明可能性。要件 2.2)

### InterestConfigLoader(infrastructure)

- **Purpose:** `interests.toml` のパース。ファイルなし・空 → 空リスト(機能無効)、辞書外キーワード → 警告ログ
- **形式:**

```toml
[[interests]]
keyword = "Kotlin"
weight = 2.0

[[interests]]
keyword = "Kafka"   # weight 省略時 1.0
```

### BuildRecommendationsUseCase(application)

- **Purpose:** 記事取得(Port)→ 各記事のスコアリング → スコア 0 除外 → 降順ソート
- **Dependencies:** ArchiveQueryPort、InterestScorer、読み込み済み Interest リスト

### RecommendController(presentation)

- **Purpose:** `GET /api/recommend?days=N`。days の検証(既存 ReportController と同じ規約で 400)
- **レスポンス:** `[{ item, score, matchedInterests: ["Kotlin", ...] }]`。興味未設定時は `{ enabled: false, items: [] }`

### UI(static/index.html)

- 「おすすめ」セクションを追加。`enabled: false` なら案内表示(要件 3.2)。静的 UI のため手動確認

## Data Models

DB 変更なし(既存 items / item_keywords を読むだけ)。新規ファイル `interests.toml`(リポジトリには example を置き、実ファイルは gitignore しない・個人リポジトリなのでコミット可)。

## Error Handling

### Error Scenarios

1. **interests.toml が存在しない / 空**
   - **Handling:** 機能無効(`enabled: false`)。起動は正常に完了する
   - **User Impact:** おすすめセクションが案内表示になるだけ
2. **interests.toml のパースエラー(不正 TOML・weight が数値でない)**
   - **Handling:** 起動時に fail-fast(設定ミスは早期発見。feeds.toml と同じ方針)
   - **User Impact:** エラーメッセージを見て修正する
3. **辞書に存在しないキーワード**
   - **Handling:** 警告ログを残してそのエントリは保持する(将来辞書に追加されれば効き始める)
   - **User Impact:** 該当興味が一致しないだけ
4. **days 不正値**
   - **Handling:** 400(既存 report と同じ)

## Testing Strategy

### Unit Testing

- InterestScorer(最重要): 一致なし → スコア 0、重み合計、新しさ減衰の境界(窓の先頭・末尾)、publishedAt null の扱い、を表駆動テスト
- InterestConfigLoader: 正常・weight 省略・ファイルなし・不正 TOML(fail-fast)・辞書外キーワード(警告)
- BuildRecommendationsUseCase: Port をモックし、スコア 0 除外・降順ソート・興味未設定時の enabled: false

### Integration Testing

- RecommendController の MockMvc テスト: レスポンス構造・days 境界値・不正値 400・未設定時のレスポンス

### End-to-End Testing

- 実データ蓄積後にブラウザで「おすすめ」セクションを手動確認
