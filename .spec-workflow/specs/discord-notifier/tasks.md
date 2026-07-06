# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。パッケージ構成は structure.md の `notify/` feature に従い、DB 読み取りは `capabilities/` の Port 経由(archive を直接 import しない)。

- [ ] 1. DigestSelectionPolicy(domain)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/domain/DigestSelectionPolicy.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/domain/DigestSelectionPolicyTest.kt
  - 投稿済み guid 除外、人気フィード優先、publishedAt 新しい順のタイブレーク、上限 N 件、候補 0 件、を表駆動テストで先に固めてから純 Kotlin で実装する
  - Purpose: 「おすすめ N 件」の選抜ルール(Phase 1 は人気フィード優先)
  - _Requirements: 1.2, 1.3, 1.4_

- [ ] 2. ArchiveQueryPort に「直近 N 時間の tech 記事」取得を追加(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/capabilities/ArchiveQueryPort.kt(メソッド追加), src/main/kotlin/dev/rockyh/rsswatch/archive/application/ArchiveQueryPortImpl.kt, archive/domain/ItemQueries.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/archive/application/ArchiveQueryPortImplTest.kt(既存に追加)
  - 共有 Testcontainers PostgreSQL で、直近 N 時間・`category = "tech"`・キーワード同梱の取得(境界値: N 時間ちょうど、tech 以外は除外)をテストしてから実装する
  - Purpose: notify が読み取る当日分 tech 記事の供給(feature 間は capabilities Port 経由)
  - _Leverage: ItemQueries / RssItemRepository / PostgresTestSupport_
  - _Requirements: 1.1, 4.1_

- [ ] 3. PostedGuidRepository(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/PostedGuidRepository.kt, src/main/resources/db/migration/V2__notify_posted_guids.sql
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/PostedGuidRepositoryTest.kt
  - 共有 Testcontainers PostgreSQL(`SharedPostgresContainer`)+ Flyway で、markPosted → postedGuids(since) の往復、同 guid 二重 mark の冪等性、since 境界値をテストしてから kuery-client で実装する(`posted_at` は TIMESTAMPTZ)
  - Purpose: 日跨ぎの二重投稿防止
  - _Leverage: RssItemRepositoryTest のテスト基盤(PostgresTestSupport / SharedPostgresContainer)_
  - _Requirements: 1.3_

- [ ] 4. ClaudeSummarizer(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/ClaudeSummarizer.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/ClaudeSummarizerTest.kt
  - MockRestServiceServer で、正常レスポンス → 3 行要約(`content[0].text` 抽出)、タイムアウト・429・不正レスポンス → Result.failure をテストしてから RestClient で実装する。Messages API(`POST /v1/messages`、ヘッダ `x-api-key` / `anthropic-version: 2023-06-01`)。既定モデル `claude-haiku-4-5`・system プロンプト・max_tokens は application.yml から注入
  - Purpose: AI 3 行要約の生成
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 5. DiscordWebhookClient(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/DiscordWebhookClient.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/DiscordWebhookClientTest.kt
  - N 件を 1 通にまとめた embed ペイロード(タイトル・URL・要約・キーワード)の構造、429 時の Retry-After 尊重リトライ、リトライ上限到達で failure をテストしてから実装する
  - Purpose: Discord への 1 通投稿
  - _Requirements: 3.1, 3.2_

- [ ] 6. BuildDigestUseCase(application)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/application/BuildDigestUseCase.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/application/BuildDigestUseCaseTest.kt
  - ArchiveQueryPort / Policy / Summarizer / WebhookClient / Repository をモックし、①通常 → 要約付き投稿 + markPosted ②要約失敗 → 要約なしフォールバック投稿 ③候補 0 → 投稿しない ④Webhook 失敗 → markPosted しない、を先にテストする
  - Purpose: 取得 → 選抜 → 要約 → 投稿 → 記録の編成
  - _Requirements: 1, 2.2, 3.2, 4.2_

- [ ] 7. DigestScheduler(presentation)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/presentation/DigestScheduler.kt, src/main/resources/application.yml(notify 設定追加)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/presentation/DigestSchedulerTest.kt
  - スケジュール発火で UseCase が呼ばれること、Webhook URL 未設定時は Bean が登録されないこと(@ConditionalOnProperty)をテストしてから、`@Scheduled(cron = ...)`(既定 `0 0 8 * * *`)で実装する
  - Purpose: 定期実行の結線と、無効時の安全起動
  - _Leverage: fetch の FetchScheduler / SchedulingConfig パターン_
  - _Requirements: 1.1, 3.3, 4.3_

- [ ] 8. README に notify(デイリーダイジェスト)の設定手順を追記
  - File: README.md
  - Webhook URL(環境変数)・ANTHROPIC_API_KEY・配信時刻(cron)・件数・人気フィード設定の説明と、無効化時の挙動(未設定なら起動のみ)を記載する
  - Purpose: 自宅サーバーでの運用手順
  - _Requirements: 3.3, Non-Functional(Security)_
