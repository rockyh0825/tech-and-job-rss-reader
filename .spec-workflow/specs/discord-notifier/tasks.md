# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。パッケージ構成は structure.md の `notify/` feature に従う。

- [ ] 1. NotificationPolicy(domain)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/domain/NotificationPolicy.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/domain/NotificationPolicyTest.kt
  - tech かつキーワードあり → 通知、jobs / キーワードなし / 投稿済み / レート上限超過 → 通知しない、を表駆動テストで先に固めてから純 Kotlin で実装する
  - Purpose: 通知対象の選別ルール(スパム化防止の核)
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 2. PostedGuidRepository(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/PostedGuidRepository.kt, src/main/resources/db/migration/V2__notify_posted_guids.sql
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/PostedGuidRepositoryTest.kt
  - 一時ファイル SQLite + Flyway で、markPosted → isPosted、同 guid 二重 mark の冪等性、countPostedSince の境界値をテストしてから kuery-client で実装する
  - Purpose: 再配信・再起動での二重投稿防止
  - _Leverage: RssItemRepositoryTest のテスト基盤_
  - _Requirements: 1.2, 4.3_

- [ ] 3. ClaudeSummarizer(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/ClaudeSummarizer.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/ClaudeSummarizerTest.kt
  - MockRestServiceServer(または WireMock)で正常レスポンス → 3 行要約、タイムアウト・429・不正レスポンス → Result.failure をテストしてから RestClient で実装する。モデル・プロンプト・max_tokens は application.yml から注入
  - Purpose: AI 3 行要約の生成
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 4. DiscordWebhookClient(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/DiscordWebhookClient.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/DiscordWebhookClientTest.kt
  - embed ペイロード(タイトル・URL・要約・キーワード)の構造、429 時の Retry-After 尊重リトライ、リトライ上限到達で failure をテストしてから実装する
  - Purpose: Discord への投稿
  - _Requirements: 3.1, 3.2_

- [ ] 5. NotifyItemUseCase(application)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/application/NotifyItemUseCase.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/application/NotifyItemUseCaseTest.kt
  - Policy / Repository / Summarizer / WebhookClient をモックし、①通知対象 → 要約付き投稿 + markPosted ②要約失敗 → 要約なしフォールバック投稿 ③投稿済み → 何もしない ④Webhook 失敗 → ログのみで例外を上げない、を先にテストする
  - Purpose: 選別 → 要約 → 投稿の編成
  - _Requirements: 1, 2.2, 3.2_

- [ ] 6. NotifyConsumer(presentation)を実装(EmbeddedKafka 結合テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/presentation/NotifyConsumer.kt, src/main/resources/application.yml(notify 設定追加)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/NotifyConsumerIntegrationTest.kt
  - EmbeddedKafka で publish → notify 消費 → WebhookClient(モック)到達、同 guid 再配信で 1 回のみ投稿、Webhook URL 未設定時は Bean が登録されないことを検証してから、groupId = "notify" + @ConditionalOnProperty で実装する
  - Purpose: 3 つ目の consumer group としての結線
  - _Leverage: SinkConsumerIntegrationTest のパターン_
  - _Requirements: 3.3, 4.1, 4.2, 4.3_

- [ ] 7. README に notify 機能の設定手順を追記
  - File: README.md
  - Webhook URL(環境変数)・ANTHROPIC_API_KEY・レート制限設定の説明と、無効化時の挙動(未設定なら起動のみ)を記載する
  - Purpose: 自宅サーバーでの運用手順
  - _Requirements: 3.3, Non-Functional(Security)_
