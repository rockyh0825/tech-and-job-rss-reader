# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。Security 設定は `shared/config/` に置き、feature パッケージは変更しない。

- [ ] 1. RssWatchUsersProperties(ユーザー外部化設定)を実装(テスト込み)
  - File: build.gradle.kts(spring-boot-starter-security, spring-security-test 追加), src/main/kotlin/dev/rockyh/rsswatch/shared/config/RssWatchUsersProperties.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/shared/config/RssWatchUsersPropertiesTest.kt
  - 複数ユーザーのバインド、bcrypt ハッシュのままの保持、ユーザー空で起動 → fail-fast(明確なメッセージ)をテストしてから実装する
  - Purpose: DB なしで数人分のユーザーを設定管理する
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 2. SecurityConfig(認証必須化 + フォームログイン)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/shared/config/SecurityConfig.kt, src/main/resources/application.yml(auth 設定例)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/shared/config/SecurityConfigTest.kt
  - MockMvc で 未認証 `/api/report` → 401/302、未認証 `/` → ログイン誘導、認証済み → 200、`/logout` → セッション無効化 を先にテストしてから、SecurityFilterChain + InMemoryUserDetailsManager で実装する
  - Purpose: 全エンドポイントの認証必須化
  - _Leverage: RssWatchUsersProperties_
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.3_

- [ ] 3. 既存テストを認証込みでグリーンに戻す
  - File: src/test/kotlin/dev/rockyh/rsswatch/report/presentation/ReportControllerTest.kt ほか MockMvc を使うテスト
  - `@WithMockUser` / SecurityMockMvcRequestPostProcessors を適用し、全テストスイート(EmbeddedKafka 統合テスト含む)がグリーンであることを確認する
  - Purpose: 認証追加による既存機能のデグレ検知
  - _Requirements: 4.1, 4.2_

- [ ] 4. SSE の認証下動作を検証(テスト込み)
  - File: (必要な場合のみ)src/main/kotlin/dev/rockyh/rsswatch/shared/config/SecurityConfig.kt の調整
  - Test: src/test/kotlin/dev/rockyh/rsswatch/live/SseAuthIntegrationTest.kt
  - 未認証で `/api/stream` → 401/302、認証済みセッションで SSE 接続 → イベント受信できることを検証する(EventSource は Cookie 認証で動くことの担保)
  - Purpose: ログイン後に SSE が追加操作なしで機能すること
  - _Requirements: 1.1, 3.2_

- [ ] 5. README に外部公開手順を追記
  - File: README.md
  - ユーザー設定(環境変数 + bcrypt ハッシュの作り方)、HTTPS 化の選択肢(リバースプロキシ / Cloudflare Tunnel 等)、公開前チェックリスト(ユーザー未設定なら起動しないこと)を記載する
  - Purpose: 自宅サーバーからの安全な外部公開手順
  - _Requirements: Non-Functional(Security)_
