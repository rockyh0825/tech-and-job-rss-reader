# Requirements Document

## Introduction

蓄積・集計 DB を SQLite から PostgreSQL(Docker Compose 管理)へ移行する。MVP では運用の手軽さを優先して SQLite を採用したが、リポジトリ層に閉じ込めた SQLite 依存(`INSERT OR IGNORE`、固定桁 TEXT タイムスタンプ、単一コネクション制約)を解消し、ネイティブなタイムスタンプ型・標準的な UPSERT・並行アクセスと、将来の全文検索(`tsvector` / `pg_trgm`)への足場を得る。あわせて「compose で DB を運用する」経験を学習題材として積む。

## Alignment with Product Vision

product.md の機能は変更しない(API・UI・Kafka パイプラインの振る舞いは維持)。tech.md の決定「リポジトリ層を分離し、将来 PostgreSQL へ差し替え可能にする」を実行に移すリファクタリングであり、学習目的(実運用に近いミドルウェア構成の経験)にも合致する。

## Requirements

### Requirement 1: PostgreSQL への蓄積(既存機能の同等動作)

**User Story:** As a ユーザー, I want DB が PostgreSQL に替わっても今までと同じように蓄積・集計・表示されること, so that 機能を失わずに基盤だけを強化できる

#### Acceptance Criteria

1. WHEN sink consumer がメッセージを受信した THEN sink SHALL PostgreSQL へ冪等に書き込む(同じ `guid` の再配信で重複行を作らない。`ON CONFLICT DO NOTHING`)
2. WHEN `GET /api/report?days=N` を呼んだ THEN API SHALL 移行前と同じレスポンス構造(クロスセクション・技術記事一覧・求人一覧)を返す
3. WHEN 全テストを実行した THEN 既存のテストスイート SHALL PostgreSQL に対して全件パスする(検証内容は維持する。唯一の例外として、タイムスタンプがマイクロ秒精度になることに伴い、ナノ秒精度に依存する境界値テストはマイクロ秒基準に書き換える)
4. IF PostgreSQL が一時停止している THEN sink SHALL オフセットをコミットせず、復旧後の再配信で取りこぼしなく蓄積する(既存の at-least-once + 冪等の性質を維持)

### Requirement 2: ネイティブ型の活用

**User Story:** As a 開発者, I want タイムスタンプが本物のタイムスタンプ型で保存されること, so that 「固定桁 TEXT の辞書順 = 時系列順」という SQLite 用の回避策を廃止できる

#### Acceptance Criteria

1. WHEN item を保存する THEN リポジトリ SHALL `published_at` / `fetched_at` を `TIMESTAMPTZ` として保存する
2. WHEN 期間フィルタ・時系列ソートを行う THEN クエリ SHALL 文字列比較ではなくタイムスタンプ型の比較で行う
3. WHEN 移行が完了した THEN コードベース SHALL 固定桁 ISO-8601 TEXT へのフォーマット処理を含まない
4. タイムスタンプの精度はマイクロ秒とする(`TIMESTAMPTZ` の精度上限。ナノ秒は切り捨てられることを許容する)

### Requirement 3: Docker Compose での DB 運用

**User Story:** As a 運用者(自分), I want PostgreSQL が Kafka と同じ compose で立ち上がること, so that ローカルと自宅サーバーで同じ手順のまま運用できる

#### Acceptance Criteria

1. WHEN `docker compose up` を実行した THEN PostgreSQL SHALL Kafka と同時に起動し、データは named volume に永続化される
2. WHEN ホスト外からアクセスを試みた THEN PostgreSQL のポート SHALL ループバック限定 bind により LAN に露出しない(kafka・kafka-ui と同じ方針)
3. WHEN 接続情報を変えたい THEN アプリ SHALL 環境変数(URL・ユーザー・パスワード)で接続先を上書きできる(デフォルトは compose のローカル値)
4. WHEN スキーマを適用する THEN Flyway SHALL PostgreSQL 用に書き直したマイグレーションを起動時に自動適用する(SQLite 時代のマイグレーション履歴は引き継がない)

### Requirement 4: テストの PostgreSQL 化

**User Story:** As a 開発者, I want テストが本番と同じ PostgreSQL に対して走ること, so that SQLite との方言差によるすり抜けをなくせる

#### Acceptance Criteria

1. WHEN リポジトリ層・結合テストを実行した THEN テスト SHALL Testcontainers が起動する PostgreSQL に対して実行される
2. WHEN `./gradlew test` を実行した THEN テスト SHALL Docker が動いていれば追加の手動セットアップなしで完走する
3. IF テスト対象が DB に依存しない(domain の単体テスト等) THEN テスト SHALL コンテナを起動せずに従来どおり高速に実行される

### Requirement 5: 既存データの扱いと運用手順の更新

**User Story:** As a 運用者(自分), I want 移行のやり方と既存データの扱いが明文化されていること, so that 自宅サーバーで迷わず切り替えられる

#### Acceptance Criteria

1. 既存 SQLite ファイルからのデータ移行は行わない。Kafka の retention 内のメッセージから再蓄積できることを移行手順として文書化する(sink consumer group のオフセットをリセットして catch-up させる)
2. WHEN 移行が完了した THEN README・systemd unit 例・steering ドキュメント(tech.md 等) SHALL PostgreSQL 前提の記述に更新されている
3. WHEN 移行が完了した THEN コードベース SHALL SQLite 関連の依存・回避策(sqlite-jdbc、SqliteDialectProvider、HikariCP の単一コネクション制約)を含まない

## Non-Functional Requirements

### Code Architecture and Modularity

- **Single Responsibility Principle**: DB 方言依存は引き続き archive/infrastructure に閉じ込める(structure.md のレイヤー責務を維持)
- **domain の純粋性**: domain 層は今回も DB 実装に依存しない(ArchitectureTest で強制済み)

### Performance

- 個人ツールの規模(1 日数百件)では性能要件はゆるい。HikariCP の pool-size 1 制約を外し、デフォルトのプール設定に戻す

### Reliability

- at-least-once + 冪等書き込みの性質を維持する(Requirement 1.4)
- DB がプロセス外になるため、アプリ起動時に DB 未起動なら Flyway で起動失敗する。systemd の自動再起動(既存設定)で回復する

### Usability

- ローカル開発の手順は引き続き「compose up → bootRun」の 2 コマンドを維持する
