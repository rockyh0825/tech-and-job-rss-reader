# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。パッケージ構成は structure.md の `recommend/` feature に従う。archive へは ArchiveQueryPort 経由でアクセスする。

- [ ] 1. Interest / InterestScorer(domain)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/recommend/domain/Interest.kt, recommend/domain/InterestScorer.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/recommend/domain/InterestScorerTest.kt
  - 一致なし → 0、単一/複数一致の重み合計、新しさ減衰の境界(窓の先頭 = 1.0・末尾 = 0.5)、publishedAt null、一致キーワード一覧の返却、を表駆動テストで先に固めてから純 Kotlin で実装する
  - Purpose: レコメンドの核となるスコアリングルール(説明可能)
  - _Requirements: 2.1, 2.2_

- [ ] 2. KeywordExtractionPort に正規化名一覧の取得を追加(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/capabilities/KeywordExtractionPort.kt, keywords/application/KeywordExtractionPortImpl.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/keywords/application/KeywordExtractionPortImplTest.kt(既存があれば追記)
  - `normalizedNames(): Set<String>` を Port に後方互換で追加し、辞書の正規化名全件が返ることをテストしてから実装する
  - Purpose: interests.toml の語彙検証(タイポ検知)の材料
  - _Leverage: keywords/domain/Keywords 辞書_
  - _Requirements: 1.4_

- [ ] 3. InterestConfigLoader(infrastructure)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/recommend/infrastructure/InterestConfigLoader.kt, interests.toml(example 含む)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/recommend/infrastructure/InterestConfigLoaderTest.kt
  - 正常パース・weight 省略時 1.0・ファイルなし → 空(無効)・不正 TOML → fail-fast・辞書外キーワード → 警告ログ、をテストしてから実装する
  - Purpose: 「ファイルに雑に書くだけ」の興味定義
  - _Leverage: fetch/infrastructure/FeedConfigLoader のパターン, KeywordExtractionPort_
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 4. BuildRecommendationsUseCase(application)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/recommend/application/BuildRecommendationsUseCase.kt(必要なら capabilities/ArchiveQueryPort に後方互換のメソッド追加)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/recommend/application/BuildRecommendationsUseCaseTest.kt
  - ArchiveQueryPort をモックし、スコア 0 の除外・スコア降順ソート・興味未設定時の無効応答、をテストしてから実装する
  - Purpose: 記事取得 → スコアリング → 並べ替えの編成
  - _Leverage: ArchiveQueryPort, InterestScorer_
  - _Requirements: 2.1, 2.3_

- [ ] 5. RecommendController(presentation)を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/recommend/presentation/RecommendController.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/recommend/presentation/RecommendControllerTest.kt
  - MockMvc で レスポンス構造(item + score + matchedInterests)・days 境界値・不正値 400・興味未設定時 `enabled: false`、を先にテストしてから実装する(規約は既存 ReportControllerTest に合わせる)
  - Purpose: GET /api/recommend の公開
  - _Leverage: report/presentation/ReportController の規約_
  - _Requirements: 2.1, 2.2, 2.4, 3.2_

- [ ] 6. UI に「おすすめ」セクションを追加
  - File: src/main/resources/static/index.html(+ 必要なら js/css)
  - スコア順の推薦記事 + 一致キーワード表示。`enabled: false` 時は「interests.toml に興味を設定してください」の案内(静的 UI のため手動確認)
  - Purpose: クロスリンクと同じ画面で興味軸の一覧を見る
  - _Requirements: 3.1, 3.2_

- [ ] 7. README に interests.toml の書き方を追記
  - File: README.md
  - フォーマット(keyword + weight)、語彙は keywords 辞書の正規化名を使うこと、未設定時の挙動を記載する
  - Purpose: 運用手順
  - _Requirements: 1.1, 1.3_
