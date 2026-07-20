# Requirements Document

## Introduction

エンドポイントのパフォーマンス(特に `GET /api/report`)を Grafana で閲覧できるようにする。アプリに Spring Boot Actuator + Micrometer(Prometheus レジストリ)を追加してメトリクスを公開し、既存の Docker Compose に Prometheus(収集・保持)と Grafana(可視化)を足す。

主目的は**工夫前後の性能比較**。`/api/report` のクエリ改善やキャッシュ導入などの施策をデプロイした際に、Prometheus の時系列保持(90 日)によって「改善デプロイ前後のレイテンシ・スループット」を同一ダッシュボード上で比較できるようにする。

## Alignment with Product Vision

- tech.md「自宅サーバーで常駐運用する Web アプリケーション」の運用面の強化。既存パイプライン(fetch → Kafka → sink → DB → report)には手を入れず、観測の層を横に足すだけ
- インフラは既存の `docker/docker-compose.yml` に相乗りし(kafka-ui と同じ「ループバック限定の運用ツール」方針)、デプロイ方針(fat jar + systemd)は不変

## Requirements

### Requirement 1: アプリのメトリクス公開(Actuator + Micrometer)

**User Story:** As a 運用者, I want アプリがエンドポイント別のリクエスト数・レイテンシを Prometheus 形式で公開すること, so that 外部の Prometheus が定期収集できる

#### Acceptance Criteria

1.1. `spring-boot-starter-actuator` + `micrometer-registry-prometheus` を導入し、`GET /actuator/prometheus` が 200 で Prometheus テキスト形式のメトリクスを返す
1.2. `http.server.requests` メトリクスにより、`uri`(例 `/api/report`)・`method`・`status` タグ付きでエンドポイント別のリクエスト数と所要時間が取得できる
1.3. `management.metrics.distribution.percentiles-histogram.http.server.requests: true` を設定し、Prometheus 側で `histogram_quantile()` による p95/p99 が計算できる(ヒストグラムバケットが公開される)
1.4. JVM(ヒープ・GC・スレッド)・HikariCP(コネクションプール)・Kafka consumer のメトリクスは Actuator/Micrometer の自動計装でそのまま公開される(個別実装は不要。ダッシュボードから参照できること)
1.5. `GET /actuator/health` が 200 を返す(死活監視用)

### Requirement 2: 公開範囲の最小化

**User Story:** As a 運用者, I want actuator の公開エンドポイントが必要最小限であること, so that 環境変数や設定内容(DB パスワード等を含み得る)を晒さない

#### Acceptance Criteria

2.1. expose するのは `health` と `prometheus` のみ(`management.endpoints.web.exposure.include: health,prometheus`)
2.2. `GET /actuator/env`・`/actuator/beans`・`/actuator/configprops` 等、上記以外の actuator エンドポイントは 404 を返す

### Requirement 3: AccessJwtFilter との共存

**User Story:** As a 運用者, I want 任意ハードニング(`rss-watch.access.aud` 設定時の Cloudflare Access JWT 検証)を有効にしても Prometheus の scrape が通ること, so that ハードニングと観測を両立できる

#### Acceptance Criteria

3.1. `rss-watch.access.aud` 設定時(AccessJwtFilter 有効)でも、`/actuator/health` と `/actuator/prometheus` は `Cf-Access-Jwt-Assertion` ヘッダなしで 200 を返す(localhost の Prometheus コンテナは Access を経由できないため)
3.2. actuator 以外のパス(`/api/report` 等)は従来どおり、ヘッダなしなら 401 で拒否される(既存挙動の維持)
3.3. `rss-watch.access.aud` 未設定時の挙動は一切変わらない(フィルタ未登録のまま)

### Requirement 4: Prometheus による収集・保持

**User Story:** As a 運用者, I want メトリクスが自動収集され長期保持されること, so that 改善デプロイの前後(数週間〜数ヶ月スパン)を比較できる

#### Acceptance Criteria

4.1. `docker/docker-compose.yml` に prometheus サービスを追加し、ホスト上のアプリ(systemd / bootRun の `:8080`)の `/actuator/prometheus` を 15 秒間隔で scrape する
4.2. 保持期間は 90 日(`--storage.tsdb.retention.time=90d`)とし、データは named volume で永続化する
4.3. Prometheus の UI/API はループバック限定 bind(`127.0.0.1:9090:9090`。既存 kafka-ui と同方針で LAN に露出しない)
4.4. scrape 設定は `docker/prometheus/prometheus.yml` としてリポジトリにコミットする
4.5. scrape target の指定は Linux 本番(systemd)と macOS 開発(bootRun)の両方で動く方式とする

### Requirement 5: Grafana ダッシュボード

**User Story:** As a 運用者, I want ブラウザでエンドポイント別のパフォーマンスをダッシュボードで眺められること, so that `/api/report` の性能変化がひと目で分かる

#### Acceptance Criteria

5.1. `docker/docker-compose.yml` に grafana サービスを追加する。ループバック限定 bind とし、自宅サーバーで homepage が `:3000` を使用中のため `127.0.0.1:3001:3000` にする
5.2. datasource(Prometheus)とダッシュボードは provisioning(`docker/grafana/provisioning/`)でリポジトリ管理し、手動セットアップなしで起動直後から閲覧できる
5.3. ダッシュボード JSON をコミットし、少なくとも次のパネルを持つ: エンドポイント別レイテンシ(p50/p95/p99)・エンドポイント別リクエストレート・エラー率(5xx 比率)・JVM ヒープ・HikariCP コネクションプール・Kafka consumer(records-consumed レート。要件 1.4 のメトリクスをダッシュボードから参照できることの担保)。レイテンシパネルのクエリ(または `uri` 変数の既定値)は長寿命 SSE 接続の `/api/stream` を除外する
5.4. 匿名閲覧(`GF_AUTH_ANONYMOUS_ENABLED=true`、Viewer ロール)で開けること。admin パスワードは `docker/.env` から注入し、リポジトリにコミットしない
5.5. Grafana のデータ(手元で加えたダッシュボード編集等)は named volume で永続化する

### Requirement 6: 外部公開(Cloudflare Tunnel + Access)

**User Story:** As a 運用者, I want 外出先からも Grafana を見られること, so that 自宅 LAN にいなくても性能を確認できる

#### Acceptance Criteria

6.1. 既存 cloudflared トンネルに Public Hostname `grafana.<ドメイン>` → `http://localhost:3001` を追加し、Access で保護する(rss-watch の UI とは別ホスト名)。これは Cloudflare ダッシュボード操作のみでリポジトリ成果物はない(ユーザー作業)
6.2. `docs/public-access.md` に Grafana 公開の手順(Public Hostname 追加 + Access アプリ追加)を追記する

## Non-Functional Requirements

### Code Architecture and Modularity

- アプリ側の変更は依存追加 + `application.yml` の management 設定 + AccessJwtFilter の除外パスのみ。feature パッケージは増やさない(Actuator は Spring Boot の横断機能であり、shared/config の管轄)
- 既存 feature(fetch / archive / live / report / notify / keywords)のコードは変更しない

### Security

- actuator の expose は `health,prometheus` のみ(Requirement 2)。Prometheus・Grafana はループバック限定 bind で、インターネットからの経路は Cloudflare Access 保護のトンネルのみ
- Grafana の admin パスワード・トンネルトークンは `docker/.env` 注入でコミットしない

### Performance

- `percentiles-histogram` によるメトリクス増(バケット数 × URI 数)は数百系列程度で、15 秒間隔の scrape・90 日保持でもディスク・CPU 負荷は自宅サーバーで無視できる規模
- メトリクス計測(Micrometer のタイマー)のリクエストあたりオーバーヘッドはマイクロ秒オーダーで、`/api/report` の応答時間に影響しない

### Reliability

- Prometheus / Grafana の停止・障害はアプリ本体(パイプライン・Web UI)に影響しない(scrape は pull 型で、アプリは公開するだけ)
