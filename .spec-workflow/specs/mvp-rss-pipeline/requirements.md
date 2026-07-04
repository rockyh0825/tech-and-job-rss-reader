# Requirements Document

## Introduction

MVP として、RSS 収集からクロスリンク表示までの一連のパイプラインを構築する。fetcher が技術系・求人系フィードを定期巡回してキーワード抽出済みメッセージを Kafka に publish し、sink consumer が DB へ冪等に蓄積、live consumer が SSE でブラウザへ配信する。ブラウザでは「求人で言及されている技術 × 関連記事」のクロスリンクと新着のリアルタイム表示ができる。

## Alignment with Product Vision

product.md の Key Features 1〜5 をすべてカバーする初回リリース。同時に「Kafka の consumer group・オフセット管理・冪等 sink を実データで学ぶ」という学習目的を満たす構成(1 topic + 2 consumer group)にする。

## Requirements

### Requirement 1: フィード巡回と publish(fetcher)

**User Story:** As a ユーザー, I want 設定したフィードを定期巡回して新着エントリが自動で流れてくること, so that 手動巡回なしに技術・求人情報が蓄積される

#### Acceptance Criteria

1. WHEN アプリが起動している THEN fetcher SHALL `@Scheduled` により設定間隔で全フィードを巡回する
2. WHEN フィードを取得した THEN fetcher SHALL Rome でパースし、タイトル + 概要から技術キーワードを抽出した上で、topic `rss.items` に key = フィード名で publish する
3. IF 個別フィードの取得・パースに失敗した THEN fetcher SHALL そのフィードをスキップしてログを残し、残りのフィードの巡回を継続する
4. WHEN `feeds.toml` に行を追加した THEN システム SHALL コード変更なしで(再起動のみで)新フィードを巡回対象に含める
5. フィード定義は `category = "tech" | "jobs"` の 2 分類を持つこと

### Requirement 2: キーワード抽出

**User Story:** As a ユーザー, I want 記事・求人のテキストから技術キーワードが自動抽出されること, so that 技術単位の集計とクロスリンクができる

#### Acceptance Criteria

1. WHEN テキストを照合する THEN 抽出器 SHALL 「正規化名 + エイリアス」辞書(約 60 分類)に基づき、表記ゆれを正規化名に寄せて返す
2. WHEN 日本語文中に英語キーワードが埋め込まれている(例: `Pythonで`) THEN 抽出器 SHALL 独自境界 `(?<![A-Za-z0-9])...(?![A-Za-z0-9+#])` により正しく検出する
3. IF キーワードが `Go` のような一般語と衝突する短い名前である THEN 抽出器 SHALL 大文字小文字を区別する別枠(`Go` 完全一致 + `golang`)で照合する

### Requirement 3: 冪等な蓄積(sink consumer)

**User Story:** As a ユーザー, I want 再配信や再起動があってもデータが重複・欠落しないこと, so that 集計結果を信頼できる

#### Acceptance Criteria

1. WHEN sink consumer がメッセージを受信した THEN sink SHALL マイクロバッチで DB に書き込み、書き込み完了後にオフセットをコミットする
2. IF 同じ `guid` のエントリが再度届いた THEN sink SHALL 重複行を作らない(`guid UNIQUE` + `INSERT OR IGNORE` 相当)
3. WHEN sink を停止して再起動した THEN sink SHALL 自分のオフセットから未処理分を catch-up し、全件が DB に入る

### Requirement 4: リアルタイム配信(live consumer + SSE)

**User Story:** As a ユーザー, I want 新着が届いた瞬間にブラウザ画面に流れてくること, so that 巡回のたびにリロードしなくてよい

#### Acceptance Criteria

1. WHEN live consumer がメッセージを受信した THEN live SHALL 1 件ずつ即時に SSE で接続中のブラウザへ配信する
2. WHEN sink consumer が停止している THEN live SHALL 影響を受けずに配信を継続する(consumer group が独立している)
3. IF SSE クライアントが切断した THEN サーバー SHALL 該当接続をクリーンアップし、他クライアントへの配信を継続する

### Requirement 5: レポート / 集計 API とブラウザ UI

**User Story:** As a ユーザー, I want 「求人で言及されている技術」ランキングとその技術の記事を一画面で見られること, so that 市場で求められている技術と学習リソースがつながる

#### Acceptance Criteria

1. WHEN 集計 API を直近 N 日指定で呼んだ THEN API SHALL ①求人で言及された技術 × 関連記事のクロスセクション ②技術記事一覧 ③求人一覧 を返す
2. WHEN ブラウザで画面を開いた THEN UI SHALL クロスリンク(求人言及数の多い技術順に、実際の求人と同じ技術の記事を並べたもの)を表示する
3. WHEN 新着が SSE で届いた THEN UI SHALL リロードなしで新着欄に追記する

## Non-Functional Requirements

### Code Architecture and Modularity

- **Single Responsibility Principle**: fetch / keywords / consumer / db / web を structure.md のパッケージ境界どおりに分離する
- **リポジトリ層の分離**: SQLite 依存は db 層に閉じ込め、将来 PostgreSQL に差し替え可能にする

### Performance

- 個人ツールとして十分であればよい(1 日数回 × 数百件)。低遅延が要るのは live 側のみ

### Reliability

- at-least-once 配信 + 冪等書き込みでデータ欠落・重複を防ぐ
- プロセス停止時は systemd の自動再起動で巡回を再開する

### Usability

- フィード追加は `feeds.toml` に行を足すだけ。キーワード追加は辞書に 1 行足すだけ
