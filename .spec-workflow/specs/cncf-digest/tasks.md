# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。DB 読み取りは `capabilities/ArchiveQueryPort` の既存メソッド `itemsByCategory` を再利用する(Port にメソッドを追加しない)。

- [x] 1. `ItemCategory.CNCF` を追加(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/shared/contract/ItemCategory.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/fetch/infrastructure/FeedConfigLoaderTest.kt(`category = "cncf"` のパースケース追加)
  - Purpose: カテゴリ語彙の 1 箇所管理を維持したまま cncf を追加
  - _Requirements: 1.1, 1.3_

- [x] 2. CncfProjects 辞書 + CncfProjectMatcher(domain)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/domain/CncfProjects.kt, CncfProjectMatcher.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/domain/CncfProjectsTest.kt(名前一意・全エントリ成熟度あり), CncfProjectMatcherTest.kt(境界マッチ・exact-case・非マッチ)
  - KeywordExtractor の境界正規表現イディオムを複製(出典コメント付き)。graduated 全件 + incubating 全件 + sandbox 厳選
  - _Requirements: 3.1, 3.4_

- [x] 3. CncfDigestSelectionPolicy + CncfDigest + CncfDigestPublisher(domain)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/domain/CncfDigestSelectionPolicy.kt, CncfDigest.kt, CncfDigestPublisher.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/domain/CncfDigestSelectionPolicyTest.kt(tier 順・tier 内新着順・cap・0 件)
  - _Requirements: 2.3, 3.3_

- [x] 4. DiscordPoster を抽出(純リファクタ・単独コミット)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/DiscordPoster.kt, DiscordWebhookClient.kt(委譲化)
  - Test: 既存 DiscordWebhookClientTest が green のまま(挙動不変)
  - _Requirements: Non-Functional(transport 共有)_

- [x] 5. CncfDiscordWebhookClient(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/infrastructure/CncfDiscordWebhookClient.kt, notify/ConditionalOnCncfNotifyEnabled.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/infrastructure/CncfDiscordWebhookClientTest.kt(tier 別バッジ author 行・☸️ CNCF・サムネ/要約 field・CTA・429 リトライ・部分失敗 PostOutcome)
  - _Requirements: 2.2, 3.2_

- [x] 6. BuildCncfDigestUseCase(application)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/application/BuildCncfDigestUseCase.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/application/BuildCncfDigestUseCaseTest.kt(posted 除外・0 件スキップ・要約/サムネ失敗フォールバック・markPosted は実投稿分のみ・cap)
  - _Requirements: 2.1, 2.3, 2.4, 2.5_

- [x] 7. CncfDigestScheduler + 条件付き Bean 再編を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/notify/presentation/CncfDigestScheduler.kt, notify/ConditionalOnAnyNotifyEnabled.kt, ClaudeSummarizer.kt / OgpThumbnailResolver.kt / PostedGuidRepository.kt(アノテーション差し替え)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/notify/presentation/CncfDigestSchedulerTest.kt, NotifyFeatureToggleTest.kt(CNCF のみ / 両方 / どちらもなしの 3 コンテキスト追加)
  - _Requirements: 4.1, 4.2, 4.4_

- [x] 8. 設定 + フィード + README を追加
  - File: src/main/resources/application.yml(pool.size 3、rss-watch.notify.cncf ブロック), feeds.toml(CNCF Blog / Kubernetes Blog), README.md(RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL の運用手順)
  - _Requirements: 1.1, 1.2, Non-Functional(Security / Performance)_
