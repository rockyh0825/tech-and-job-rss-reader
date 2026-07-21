# Requirements Document

## Introduction

`GET /api/report` のレイテンシは observability spec(Prometheus + Grafana)で見えるようになったが、「1 リクエストのどこで時間を食っているか」の内訳は見えない。分散トレーシングを導入し、リクエスト単位のスパンのウォーターフォール図(HTTP 処理 → 個々の SQL → レスポンス書き出し)を閲覧できるようにする。

アプリには Spring Boot 3 標準の Micrometer Tracing(OpenTelemetry ブリッジ)+ OTLP エクスポータを追加し、バックエンドには Grafana Tempo を採用する(既存 Grafana に datasource を足すだけで閲覧でき、compose 追加が 1 コンテナで最小)。本 spec の核心価値は **SQL スパン**: HTTP サーバースパンだけでは「どの SQL が遅いか」が見えないため、JDBC 観測ライブラリ(datasource-micrometer)でクエリ単位のスパンを出す。直近の `/api/report` N+1 解消(#59)はコードリーディングで特定したが、トレースがあればウォーターフォール上の「同型 SQL スパンの繰り返し」として一目で見つけられた — 次の性能調査をその形でできるようにする。

## Alignment with Product Vision

- tech.md「自宅サーバーで常駐運用する Web アプリケーション」の運用面の強化。observability spec と同じく、既存パイプライン(fetch → Kafka → sink → DB → report)には手を入れず、観測の層を横に足すだけ
- インフラは既存の `docker/docker-compose.yml` に相乗りし(Prometheus / Grafana と同じ「ループバック限定の運用ツール」方針)、デプロイ方針(fat jar + systemd)は不変
- 閲覧 UI は既存 Grafana に集約する(新しい UI・公開ホスト名は増やさない)

## Requirements

### Requirement 1: アプリのトレース出力(Micrometer Tracing + OTLP)

**User Story:** As a 運用者, I want アプリが HTTP リクエストごとにトレース(サーバースパン)を生成して OTLP で送信すること, so that リクエスト単位で処理の内訳を追跡できる

#### Acceptance Criteria

1.1. `io.micrometer:micrometer-tracing-bridge-otel` と `io.opentelemetry:opentelemetry-exporter-otlp` を導入する(バージョンは Spring Boot 3.5.6 の dependency management に任せる。micrometer-tracing 1.5.4 / opentelemetry 1.49.0 が解決されることを確認済み)
1.2. HTTP リクエストごとにサーバースパン(`http.server.requests` observation 由来。`uri`・`method`・`status` タグ付き)が生成され、OTLP/HTTP で `http://127.0.0.1:4318/v1/traces`(ローカルの Tempo)へ送信される。`management.otlp.tracing.endpoint` は**明示的に設定する**(Spring Boot 3.5 では endpoint 未設定だとエクスポータ Bean 自体が作られない — `@ConditionalOnProperty("management.otlp.tracing.endpoint")` を確認済み)
1.3. サンプリングは全量(`management.tracing.sampling.probability: 1.0`。既定は 0.1)。自宅規模でトラフィックが少なく保存コストが無視でき、性能調査には「遅かったあのリクエスト」が確実に残っている必要があるため
1.4. トレースの service name は `spring.application.name`(`tech-and-job-rss-reader`)になる
1.5. actuator の expose は増やさない(`health,prometheus` のまま。observability spec の要件 2 を維持)
1.6. アプリログに traceId/spanId の相関 ID が載る(Micrometer Tracing がクラスパスにあると Spring Boot 3.2+ はログパターンに相関 ID を自動で含める。「ログのこのエラーはどのトレースか」を突き合わせられる)

### Requirement 2: SQL スパン(JDBC 観測 — 本 spec の核心価値)

**User Story:** As a 運用者, I want 1 リクエスト内で実行された個々の SQL がスパンとして見えること, so that 「どの SQL が遅いか」「SQL の後のレスポンス書き出しに何 ms かかったか」を切り分けられる

#### Acceptance Criteria

2.1. `net.ttddyy.observation:datasource-micrometer-spring-boot`(Spring Boot 3 対応の JDBC 観測ライブラリ。現行最新 1.1.1)を導入し、DataSource 経由のクエリ実行ごとに SQL 文つきのスパンが生成される
2.2. `/api/report` のトレースで、HTTP サーバースパンの子として実行された各 SQL のスパンがウォーターフォールに並び、「各 SQL の所要時間」と「最後の SQL 完了からレスポンス完了までの時間(= 集計・シリアライズ・書き出し)」が読み取れる
2.3. N+1 が発生している場合、同型 SQL スパンの繰り返しとしてウォーターフォール上で視認できる(#59 で解消した形の再発を目視検出できる)
2.4. スパンに SQL 文は含めるが、バインドパラメータ値は含めない(datasource-micrometer の既定。既定で値が含まれないことは実装時に実出力で検証する)

### Requirement 3: Tempo による収集・保持

**User Story:** As a 運用者, I want トレースが自動収集され一定期間保持されること, so that 「昨日遅かったリクエスト」を後から調査できる

#### Acceptance Criteria

3.1. `docker/docker-compose.yml` に tempo サービス(`grafana/tempo` タグ固定、`container_name: rss-watch-tempo`)を追加する
3.2. OTLP/HTTP 受信ポートはループバック限定 bind(`127.0.0.1:4318:4318`。既存 kafka-ui / Prometheus / Grafana と同方針で LAN に露出しない)。Tempo の API ポート(3200)はホストへ publish しない(Grafana から compose 内 DNS で届くため)
3.3. 保持期間は 14 日(`block_retention: 336h`)とし、データは named volume で永続化する。トレースはメトリクスより容量を食い、用途が「直近の性能調査」なので Prometheus の 90 日は不要
3.4. Tempo 設定ファイルは `docker/tempo/tempo.yml` としてリポジトリにコミットする
3.5. 追加ポートは既存(8080 / 8081 / 9090 / 9092 / 3001 / 5432、自宅サーバーの homepage :3000)と衝突しない(4318 のみ追加)

### Requirement 4: Grafana からの閲覧

**User Story:** As a 運用者, I want 既存 Grafana でトレースのウォーターフォール図を見られること, so that 新しい UI を覚えたり公開したりせずに済む

#### Acceptance Criteria

4.1. `docker/grafana/provisioning/datasources/` に Tempo datasource を追加する(url は compose 内 DNS の `http://tempo:3200`。手動セットアップなしで起動直後から使える)
4.2. Grafana の Explore で TraceQL(例: `{resource.service.name="tech-and-job-rss-reader" && span:duration > 500ms}`)によりトレースを検索し、ウォーターフォール図を表示できる。閲覧手順は docs に記載する
4.3. exemplars(メトリクスのグラフ点からトレースへのジャンプ)は**スコープ外**とし、将来課題として明記する(設定が複雑になる割に、自宅規模では Explore の TraceQL 検索で十分)

### Requirement 5: Kafka リスナーのトレース(任意)

**User Story:** As a 運用者, I want Kafka メッセージの消費にもスパンが付くこと, so that HTTP 以外の処理(live の SSE 中継)も同じ仕組みで追える

#### Acceptance Criteria

5.1. live リスナーの消費処理にスパン(spring-kafka の observation)が付く。有効化は `ContainerProperties` の observation フラグで行う(`spring.kafka.listener.observation-enabled` プロパティは**自動構成のコンテナファクトリにしか効かない**。本コードベースの `shared/config/KafkaConfig.kt` はファクトリを自前組みしているため、ファクトリ側での有効化が必要 — design の「Kafka observation」参照)
5.2. sink はバッチリスナーであり、spring-kafka の observation はバッチリスナーをサポートしない(スパンは付かない)。この制約を design に明記する
5.3. observation 有効化により既存の `spring.kafka.listener` タイマー(observability spec の Grafana「Kafka リスナー」パネルが参照)がobservation 由来のメトリクスに置き換わりタグ体系が変わるため、パネルがデグレしないことを確認する(必要ならパネルのクエリを追随修正する)

本要件は任意(HTTP + SQL が主目的)。5.3 のデグレ確認コストが見合わないと判断した場合は見送ってよい。

### Requirement 6: docs・steering の更新

**User Story:** As a 運用者, I want トレースの見方と運用上の注意が docs にあること, so that 数ヶ月後の自分が迷わず使える

#### Acceptance Criteria

6.1. docs(home-server.md 等)に Tempo の役割・ポート・保持期間・Grafana Explore での閲覧手順(TraceQL の例つき)を追記する
6.2. `.spec-workflow/steering/tech.md`(Key Dependencies)と `structure.md`(`docker/` の説明)を Tempo 追加後の実態に合わせる

## Non-Functional Requirements

### Code Architecture and Modularity

- アプリ側の変更は依存追加 + `application.yml` の management 設定のみ(Requirement 5 を実施する場合のみ `shared/config/KafkaConfig.kt` に 1 行加わる)。feature パッケージは増やさない(トレーシングは Spring Boot の横断機能であり、observability spec と同じ整理)
- 既存 feature(fetch / archive / live / report / notify / keywords)のコードは変更しない

### Security

- Tempo はループバック限定 bind で、インターネットからの経路はない(Grafana 経由の閲覧のみで、Grafana への経路は従来どおりループバック + Cloudflare Access 保護のトンネル)
- トレースにはリクエスト URI・SQL 文が入る(バインド値は入れない。要件 2.4)。このアプリは個人利用で、URI にも SQL にも第三者の機微情報は含まれないため許容する(design の「トレース内容の機微情報」参照)
- AccessJwtFilter(インバウンド認証)はトレース送信(アプリ → Tempo のアウトバウンド)に無関係で、変更不要

### Performance

- 全量サンプリングでも、トレース生成のリクエストあたりオーバーヘッドはマイクロ秒〜サブミリ秒オーダーで、送信はバックグラウンドのバッチ処理(既定: 5 秒間隔・最大 512 スパン/バッチ)のためリクエスト処理をブロックしない
- トレース容量は 14 日保持 + 自宅トラフィックでは数百 MB オーダーに収まる見込み(named volume。肥大した場合は保持期間を縮める)

### Reliability

- Tempo の停止・障害はアプリ本体に影響しない(エクスポータは送信失敗時にスパンを破棄してエラーログを出すだけ。リクエスト処理は継続する)
- 既存の Prometheus メトリクス(observability spec)はトレーシング導入後も従来どおり動く(Micrometer の Observation は 1 計測からメトリクスとトレースの両方を作る仕組みで、競合しない)
