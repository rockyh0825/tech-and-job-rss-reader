# Tasks Document

アプリ側変更(Task 1)は TDD(Red → Green → Refactor)で進める(CLAUDE.md 準拠。テストが先・実装が後)。Task 2 は宣言的なインフラ定義のため自動テストは持たず、完了条件を手動確認で定義する。Task 3 は任意(design の「Kafka observation」の制約 — sink 非対応・既存メトリクスのタグ変化 — を確認した上で、見合わなければ見送ってよい)。

- [x] 1. Micrometer Tracing + OTLP + JDBC 観測の導入(TDD)
  - File: build.gradle.kts(micrometer-tracing-bridge-otel / opentelemetry-exporter-otlp / net.ttddyy.observation:datasource-micrometer-spring-boot:1.1.1 追加。Boot BOM 管理の 2 つはバージョン指定なし)、src/main/resources/application.yml(management.tracing.sampling.probability: 1.0、management.otlp.tracing.endpoint: http://127.0.0.1:4318/v1/traces — endpoint は明示必須。design の「依存と設定」参照)
  - Test: `@AutoConfigureObservability(metrics = false)` + `management.otlp.tracing.export.enabled=false` のフルコンテキストで、Tracer Bean が存在する・sampling probability が 1.0 で束縛されている・DataSource が datasource-micrometer によりプロキシされている(アサート方法は実装時にライブラリ API を確認)。既存フルコンテキストテスト 6 個が無変更で green のまま(テスト基盤が `management.tracing.enabled=false` を自動適用するため送信は起きない — design の Testing Strategy 参照)
  - 完了条件: 上記テストがすべて green で、テスト実行ログに OTLP 接続エラーが出ない。datasource-micrometer 1.1.1 と Boot 3.5.6 の互換を確認済み(合わなければ対応バージョンへ調整して design に追記)
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 2.1_

- [x] 2. docker-compose に Tempo + Grafana の Tempo datasource を追加
  - File: docker/docker-compose.yml(tempo サービス + tempo-data volume。grafana/tempo タグ固定・container_name rss-watch-tempo・`127.0.0.1:4318:4318` のみ publish)、docker/tempo/tempo.yml(新規: OTLP/HTTP 受信 0.0.0.0:4318・local backend・block_retention 336h。design の骨子を採用バージョンの公式リファレンスで確認して確定)、docker/grafana/provisioning/datasources/tempo.yml(新規: uid tempo・url http://tempo:3200)
  - 完了条件: `docker compose config` が通り、`docker compose up -d` + `./gradlew bootRun` 後に `/api/report` を数回叩くと、Grafana Explore(Tempo datasource)の TraceQL 検索でトレースがヒットし、ウォーターフォールに HTTP サーバースパン + 子の SQL スパン(SQL 文つき・バインド値なし)が並ぶ。Tempo 停止中もアプリが正常応答する(要件 2.2〜2.4 の実地確認を含む)
  - _Requirements: 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2_

- [ ] 3. (任意)live リスナーの Kafka observation 有効化
  - File: src/main/kotlin/dev/rockyh/rsswatch/shared/config/KafkaConfig.kt(liveKafkaListenerContainerFactory に `factory.containerProperties.isObservationEnabled = true`。`spring.kafka.listener.observation-enabled` プロパティは自動構成ファクトリにしか効かないため使えない — design の「Kafka observation」参照)、必要に応じて docker/grafana/provisioning/dashboards/rss-watch.json(Kafka リスナーパネルのタグ追随)
  - 制約の確認: sink(バッチリスナー)は observation 対象外でスパンが付かない。live の `spring.kafka.listener` メトリクスがタグ体系ごと変わる(`name` → `spring.kafka.listener.id` + `messaging.*`)ため、Grafana「Kafka リスナー」パネルのデグレ確認と必要な修正が完了条件に含まれる
  - 完了条件: live の消費にスパンが付き Tempo で閲覧できる。Grafana の Kafka リスナーパネルが sink・live とも引き続き描画される(タグ混在への対処込み)。既存テストが green のまま
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 4. docs・steering 更新
  - File: docs/home-server.md(Tempo の役割・ポート 4318・保持 14 日・Grafana Explore + TraceQL での閲覧手順と検索例・Tempo 停止時はエクスポート失敗ログが出るだけでアプリ無影響であること)、.spec-workflow/steering/tech.md(Key Dependencies に Tempo と Micrometer Tracing を追記)、.spec-workflow/steering/structure.md(`docker/` の説明に tempo/ を反映)
  - exemplars(メトリクス → トレースのジャンプ)と producer 側トレース連結はスコープ外・将来課題として docs に明記する
  - 完了条件: docs の手順どおりに Grafana Explore で `/api/report` のウォーターフォールへたどり着ける。steering の記述が実態(compose のサービス構成・依存一覧)と一致する
  - _Requirements: 4.3, 6.1, 6.2_
