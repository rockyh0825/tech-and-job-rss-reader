# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。**テストと実装は同一タスク**であり、テストが先・実装が後(インフラ・ドキュメントのタスクは除く)。移行の性質上「既存テストを PostgreSQL に向けて Red にする → 実装を直して Green に戻す」がサイクルの中心になる。

- [x] 1. docker-compose に PostgreSQL サービスを追加
  - File: docker/docker-compose.yml
  - `postgres:17-alpine` + named volume + healthcheck(`pg_isready`)+ ループバック限定 bind(`127.0.0.1:5432`)+ `restart: unless-stopped`。DB 名/ユーザーは `rsswatch`
  - Purpose: ローカル・自宅サーバー共通の PostgreSQL 実行環境
  - _Leverage: kafka サービスで確立した compose パターン_
  - _Requirements: 3.1, 3.2_

- [x] 2. PostgreSQL + Testcontainers の依存関係を追加
  - File: build.gradle.kts
  - 追加: `org.postgresql:postgresql`, `org.flywaydb:flyway-database-postgresql`, `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`
  - `sqlite-jdbc` の削除はここでは行わない(RssItemRepositoryTest が SQLiteDataSource を import しており、先に消すとテスト全体がコンパイルエラーになる。削除は Task 6)
  - Purpose: PostgreSQL 移行に必要な依存を揃える(全中間状態をコンパイル可能に保つ)
  - _Requirements: 3.4, 4.1_

- [x] 3. テスト基盤を Testcontainers(PostgreSQL)へ移行
  - File: src/test/kotlin/dev/rockyh/rsswatch/ 配下のテスト共通設定(共有コンテナ)
  - Test: 接続方法は 2 系統(design.md「テスト基盤」参照)。①Spring コンテキストを立てる RssWatchApplicationTest・SinkConsumerIntegrationTest・LiveConsumerIntegrationTest(現在 `jdbc:sqlite:` をハードコード)は共有コンテナ + `@ServiceConnection` へ、②Spring コンテキストなしの RssItemRepositoryTest(現在 SQLiteDataSource を手組み)は共有コンテナの `jdbcUrl` から DataSource を手組みする形へ切り替える。MockMvc テスト(ReportControllerTest・SseControllerTest は standaloneSetup で DB 不要)は対象外
  - この段階では `INSERT OR IGNORE` 等の方言差で **Red になってよい**(Red の内容が SQL 方言エラーであることを確認する)。domain 単体テスト(KeywordExtractor 等)はコンテナを起動しないことも確認する
  - Purpose: 本番と同じ DB でテストする基盤(要件 4)
  - _Leverage: spring-boot-testcontainers の @ServiceConnection_
  - _Requirements: 4.1, 4.2, 4.3_

- [x] 4. Flyway マイグレーションを PostgreSQL 用に書き直し
  - File: src/main/resources/db/migration/V1__archive_initial.sql
  - タイムスタンプ 2 列を `TIMESTAMPTZ` に変更(design.md の DDL)。履歴を引き継ぐ既存環境がないため V1 を書き換える
  - Purpose: スキーマの PostgreSQL 化(要件 2.1, 3.4)
  - _Requirements: 2.1, 3.4_

- [x] 5. RssItemRepository を PostgreSQL 対応に修正(Green 化)
  - File: src/main/kotlin/dev/rockyh/rsswatch/archive/infrastructure/RssItemRepository.kt
  - Test: 既存 RssItemRepositoryTest を Green に戻す。加えて「保存 → 読み出しで Instant がマイクロ秒精度で一致する」往復テストを先に追加する。ナノ秒境界のケース(cutoff より 1 ナノ秒古い item の除外)は TIMESTAMPTZ の格納精度(マイクロ秒)に合わせて書き換える — これは格納精度という仕様変更に伴う正当なテスト更新(design.md「タイムスタンプ精度」参照)
  - `INSERT OR IGNORE` → `ON CONFLICT DO NOTHING`、固定桁 TEXT フォーマッタを廃止して `Instant` ⇔ `OffsetDateTime`(UTC)のバインドに変更、KDoc を PostgreSQL 前提に更新
  - Purpose: 方言差の解消とネイティブ型の活用(要件 1.1, 2)
  - _Leverage: 既存の SQL 資産(集計クエリは ANSI 準拠のため流用)_
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4_

- [x] 6. SQLite 残滓の除去と接続設定の差し替え
  - File: build.gradle.kts(`sqlite-jdbc` 削除), src/main/kotlin/dev/rockyh/rsswatch/archive/infrastructure/SqliteDialectProvider.kt(削除), src/main/resources/META-INF/spring.factories(この登録 1 行だけのファイルのため、ファイルごと削除), src/main/resources/application.yml, src/test/kotlin/dev/rockyh/rsswatch/architecture/ArchitectureTest.kt(domain 禁止 import の `org.sqlite.` を `org.postgresql.` に差し替え)
  - Test: `./gradlew test` 全件 Green(ArchitectureTest 含む)
  - datasource を `RSS_WATCH_DB_URL` / `RSS_WATCH_DB_USER` / `RSS_WATCH_DB_PASSWORD`(デフォルトは compose のローカル値)に変更し、HikariCP の `maximum-pool-size: 1` を削除
  - Purpose: SQLite 依存の完全除去(要件 5.3)
  - _Requirements: 1.3, 3.3, 5.3_

- [x] 7. ドキュメント更新と移行手順の実地確認
  - File: README.md, .spec-workflow/steering/tech.md, .spec-workflow/steering/structure.md, .spec-workflow/steering/product.md
  - README: 起動手順・環境変数表・「動作確認(ローカル)」の SQLite 記述を PostgreSQL(`psql` コマンド)に更新し、既存環境向けの移行手順(SQLite ファイルは移行せず、sink group のオフセットリセットで Kafka から再蓄積。retention 超過分は失われる)を追記。tech.md: Data Storage・アーキテクチャ図・決定ログ 6/8・Known Limitations を更新。structure.md / product.md の SQLite 言及(「まず SQLite」等)も更新
  - 最後に README の動作確認手順を一通り実地で流し、パイプライン全体(fetch → Kafka → sink → PostgreSQL → report/UI、冪等性、オフセットリセットでの catch-up)を確認する。あわせて PostgreSQL を一時停止 → 再開しても sink が取りこぼしなく回復すること(要件 1.4)も確認する
  - Purpose: 運用手順の整合(要件 5.1, 5.2)
  - _Requirements: 5.1, 5.2, 1.2, 1.4_
