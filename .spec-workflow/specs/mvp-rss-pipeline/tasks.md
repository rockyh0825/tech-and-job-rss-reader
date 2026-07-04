# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。**テストと実装は同一タスク**であり、テストが先・実装が後。テストのないままタスクを完了にしない(インフラ・静的 UI・ドキュメントのタスクは除く)。

パッケージ構成は Package by Feature + Layer within Feature(`.spec-workflow/steering/structure.md` 参照)。domain は純 Kotlin、feature 間は `capabilities/` の Port 経由。

- [x] 1. Gradle プロジェクトの雛形を作成
  - File: build.gradle.kts, settings.gradle.kts, src/main/kotlin/dev/rockyh/rsswatch/RssWatchApplication.kt
  - Kotlin + Spring Boot(spring-boot-starter-web, spring-kafka)+ Rome + sqlite-jdbc を依存に追加
  - Purpose: ビルド・起動できる土台を作る
  - _Requirements: 全要件の前提_

- [x] 2. Kafka + kafka-ui の Docker Compose を作成
  - File: docker/docker-compose.yml
  - KRaft モードのシングルブローカー + kafka-ui。topic `rss.items` の自動作成設定
  - Purpose: ローカル・自宅サーバー共通の Kafka 実行環境
  - _Requirements: 1, 3, 4_

- [x] 3. ArchitectureTest(Konsist)を導入
  - File: build.gradle.kts(konsist 依存追加)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/architecture/ArchitectureTest.kt
  - UseCase は application、Controller/Consumer は presentation、Port は capabilities に置かれること、domain が Spring アノテーション・Kafka・SQLite に依存しないこと、feature 間の直接 import がないことを検証する
  - Purpose: structure.md の依存ルールを以降の全タスクで自動強制する
  - _Leverage: cleaning-app の ArchitectureTest.kt_
  - _Requirements: 全要件の横断的制約_

- [x] 4. 共有契約と feeds.toml 読み込みを実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/shared/contract/RssItem.kt, fetch/domain/FeedDefinition.kt, fetch/infrastructure/FeedConfigLoader.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/shared/contract/RssItemTest.kt, fetch/infrastructure/FeedConfigLoaderTest.kt
  - RssItem の JSON シリアライズ往復、feeds.toml のパース(正常・必須項目欠落・不正 category)をテストしてから実装する
  - Purpose: 全 feature が共有するメッセージ契約と、fetch のフィード定義読み込み
  - _Leverage: feeds.toml(Python 試作から引き継ぎ)_
  - _Requirements: 1.4, 1.5_

- [ ] 5. keywords feature を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/capabilities/KeywordExtractionPort.kt, keywords/domain/Keywords.kt, keywords/domain/KeywordExtractor.kt, keywords/application/KeywordExtractionPortImpl.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/keywords/domain/KeywordExtractorTest.kt
  - 日本語文中の検出(`Pythonで`)・`Go`/`golang` の大文字小文字区別・エイリアス正規化の表駆動テストを先に書き、正規化名 + エイリアス辞書(約 60 分類)と独自境界の正規表現を純 Kotlin の domain として実装する
  - Purpose: 記事・求人テキストからの技術キーワード抽出(最重要ドメインロジック)
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 6. fetch feature を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/fetch/application/FetchFeedsUseCase.kt, fetch/presentation/FetchScheduler.kt, fetch/infrastructure/RomeFeedParser.kt, fetch/infrastructure/KafkaItemPublisher.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/fetch/application/FetchFeedsUseCaseTest.kt
  - パース → キーワード抽出(KeywordExtractionPort)→ publish(key = フィード名)の流れと、1 フィードの失敗が他フィードに波及しないことを、parser/publisher/Port をモックした単体テストで先に固める。@Scheduled は presentation の FetchScheduler で結線する
  - Purpose: パイプラインの入口
  - _Leverage: KeywordExtractionPort, FeedConfigLoader_
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 7. RssItemRepository(archive/infrastructure)を実装(テスト込み)
  - File: build.gradle.kts(kuery-client プラグイン + flyway-core 追加), src/main/kotlin/dev/rockyh/rsswatch/archive/infrastructure/RssItemRepository.kt, src/main/resources/db/migration/V1__archive_initial.sql
  - Test: src/test/kotlin/dev/rockyh/rsswatch/archive/infrastructure/RssItemRepositoryTest.kt
  - 一時ファイル SQLite に Flyway を適用した上で、guid UNIQUE + INSERT OR IGNORE の冪等性(同 guid 再挿入で行数不変)、技術ランキング・記事/求人一覧・キーワード別記事の集計クエリをテストしてから、kuery-client の生 SQL で実装する
  - Purpose: 蓄積と集計。SQLite 依存を archive/infrastructure に閉じ込め、スキーマは Flyway で管理する
  - _Leverage: kuery-client, Flyway_
  - _Requirements: 3.2, 5.1_

- [ ] 8. SinkConsumer(archive)を実装(EmbeddedKafka 結合テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/archive/presentation/SinkConsumer.kt, archive/application/StoreItemsUseCase.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/archive/SinkConsumerIntegrationTest.kt
  - EmbeddedKafka で publish → sink → DB を検証(同 guid 再配信で行数が増えない、sink 停止 → 再起動で catch-up)してから、groupId = "sink" のバッチリスナー(バッチ書き込み成功後にオフセットコミット)を実装する
  - Purpose: 確実な蓄積(at-least-once + 冪等 sink)
  - _Leverage: RssItemRepository, spring-kafka-test_
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 9. live feature を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/live/presentation/LiveConsumer.kt, live/presentation/SseController.kt, live/application/SseBroadcaster.kt
  - Test: src/test/kotlin/dev/rockyh/rsswatch/live/application/SseBroadcasterTest.kt, live/LiveConsumerIntegrationTest.kt
  - 接続クライアントへの配信・切断クライアントのクリーンアップを単体テストで、groupId = "live" の即時消費 → SSE 到達を EmbeddedKafka で検証してから実装する
  - Purpose: リアルタイム新着表示(DB を経由しない)
  - _Requirements: 4.1, 4.2, 4.3_

- [ ] 10. report feature を実装(テスト込み)
  - File: src/main/kotlin/dev/rockyh/rsswatch/capabilities/ArchiveQueryPort.kt, archive/application/ArchiveQueryPortImpl.kt, report/application/BuildReportUseCase.kt, report/presentation/ReportController.kt(クロスリンク組み立てにロジックが出たら report/domain/ へ)
  - Test: src/test/kotlin/dev/rockyh/rsswatch/report/application/BuildReportUseCaseTest.kt, report/presentation/ReportControllerTest.kt
  - ArchiveQueryPort をモックした BuildReportUseCase の単体テスト(クロスセクションの組み立て)と、GET /api/report?days=N のレスポンス構造(①求人技術 × 関連記事クロスセクション ②技術記事一覧 ③求人一覧)・days の境界値・不正値の MockMvc テストを先に書いてから実装する
  - _Leverage: RssItemRepository(ArchiveQueryPortImpl 経由)_
  - _Requirements: 5.1_

- [ ] 11. ブラウザ UI を実装
  - File: src/main/resources/static/index.html(+ 必要なら js/css)
  - クロスリンク表示(求人言及数順)+ SSE 新着欄。素の HTML/JS で作る(静的 UI のため自動テスト対象外。ブラウザで手動確認する)
  - _Requirements: 5.2, 5.3_

- [ ] 12. 自宅サーバー向けの起動手順を整備
  - File: README.md, docker/docker-compose.yml(必要なら systemd unit 例)
  - bootJar ビルド → Docker Compose(Kafka)→ アプリ起動までの手順。systemd 自動再起動の設定例
  - _Requirements: Non-Functional(Reliability)_
