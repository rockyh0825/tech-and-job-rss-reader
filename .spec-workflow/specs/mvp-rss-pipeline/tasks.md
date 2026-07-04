# Tasks Document

- [ ] 1. Gradle プロジェクトの雛形を作成
  - File: build.gradle.kts, settings.gradle.kts, src/main/kotlin/dev/rockyh/rsswatch/RssWatchApplication.kt
  - Kotlin + Spring Boot(spring-boot-starter-web, spring-kafka)+ Rome + sqlite-jdbc を依存に追加
  - Purpose: ビルド・起動できる土台を作る
  - _Requirements: 全要件の前提_

- [ ] 2. Kafka + kafka-ui の Docker Compose を作成
  - File: docker/docker-compose.yml
  - KRaft モードのシングルブローカー + kafka-ui。topic `rss.items` の自動作成設定
  - Purpose: ローカル・自宅サーバー共通の Kafka 実行環境
  - _Requirements: 1, 3, 4_

- [ ] 3. モデルと feeds.toml 読み込みを実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/model/RssItem.kt, config/FeedConfig.kt
  - RssItem(JSON シリアライズ設定込み)と、feeds.toml をパースするフィード定義読み込み
  - Purpose: 全コンポーネントが共有する型と設定
  - _Leverage: feeds.toml(Python 試作から引き継ぎ)_
  - _Requirements: 1.4, 1.5_

- [ ] 4. KeywordExtractor を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/keywords/KeywordExtractor.kt, Keywords.kt
  - 正規化名 + エイリアス辞書(約 60 分類)、独自境界の正規表現、Go の大文字小文字区別枠
  - Purpose: 記事・求人テキストからの技術キーワード抽出
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 5. KeywordExtractor の単体テストを作成
  - File: src/test/kotlin/dev/rockyh/rsswatch/keywords/KeywordExtractorTest.kt
  - 日本語文中の検出(`Pythonで`)・`Go`/`golang`・エイリアス正規化の表駆動テスト
  - Purpose: 抽出ロジックの回帰防止(最重要ロジック)
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 6. FeedFetcher(fetcher)を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/fetch/FeedFetcher.kt
  - @Scheduled 巡回 → Rome パース → キーワード抽出 → `rss.items` へ publish(key = フィード名)。フィード単位のエラースキップ
  - Purpose: パイプラインの入口
  - _Leverage: KeywordExtractor, FeedConfig_
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 7. RssItemRepository(db 層)を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/db/RssItemRepository.kt, スキーマ初期化
  - guid UNIQUE + INSERT OR IGNORE の冪等書き込み、技術ランキング・記事/求人一覧・キーワード別記事の集計クエリ
  - Purpose: 蓄積と集計。SQLite 依存をこの層に閉じ込める
  - _Requirements: 3.2, 5.1_

- [ ] 8. SinkConsumer を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/consumer/SinkConsumer.kt
  - groupId = "sink" のバッチリスナー。バッチ書き込み成功後にオフセットコミット
  - Purpose: 確実な蓄積(at-least-once + 冪等 sink)
  - _Leverage: RssItemRepository_
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 9. LiveConsumer と SseBroadcaster を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/consumer/LiveConsumer.kt, web/SseBroadcaster.kt, web/SseController.kt
  - groupId = "live" で 1 件ずつ即時消費 → SSE 配信。切断クライアントのクリーンアップ
  - Purpose: リアルタイム新着表示
  - _Requirements: 4.1, 4.2, 4.3_

- [ ] 10. レポート / 集計 API を実装
  - File: src/main/kotlin/dev/rockyh/rsswatch/web/ReportController.kt
  - GET /api/report?days=N で ①求人技術 × 関連記事クロスセクション ②技術記事一覧 ③求人一覧 を返す
  - _Leverage: RssItemRepository_
  - _Requirements: 5.1_

- [ ] 11. ブラウザ UI を実装
  - File: src/main/resources/static/index.html(+ 必要なら js/css)
  - クロスリンク表示(求人言及数順)+ SSE 新着欄。素の HTML/JS で作る
  - _Requirements: 5.2, 5.3_

- [ ] 12. EmbeddedKafka の統合テストを作成
  - File: src/test/kotlin/dev/rockyh/rsswatch/PipelineIntegrationTest.kt
  - publish → sink → DB の検証(同 guid 再配信で行数が増えない)、sink 停止 → 再起動の catch-up 検証
  - _Leverage: spring-kafka-test_
  - _Requirements: 3.1, 3.2, 3.3, 4.2_

- [ ] 13. 自宅サーバー向けの起動手順を整備
  - File: README.md, docker/docker-compose.yml(必要なら systemd unit 例)
  - bootJar ビルド → Docker Compose(Kafka)→ アプリ起動までの手順。systemd 自動再起動の設定例
  - _Requirements: Non-Functional(Reliability)_
