# Design Document

## Overview

アプリに Spring Boot Actuator + Micrometer(Prometheus レジストリ)を足して `/actuator/prometheus` でメトリクスを公開し、`docker/docker-compose.yml` に Prometheus(15 秒間隔 scrape・90 日保持)と Grafana(provisioning でダッシュボード同梱)を追加する。アプリ側はプロダクションコードの新規クラスをほぼ持たず(AccessJwtFilter の除外パス追加のみ)、大部分が依存追加と設定・インフラ定義になる。

```
Grafana (127.0.0.1:3001) ── query ──> Prometheus (127.0.0.1:9090)
                                          │ scrape /actuator/prometheus (15s)
                                          ▼
                              host.docker.internal:8080 = ホスト上のアプリ(systemd / bootRun)
```

## Steering Document Alignment

### Technical Standards (tech.md)

- 「蓄積は DB、Kafka はパイプ」と同じ発想で「メトリクスは Prometheus、アプリは公開するだけ」。pull 型のため、Prometheus / Grafana が止まってもアプリ本体は無影響
- 運用ツールを compose に相乗りさせる方針(kafka-ui と同格)。デプロイ方針(fat jar + systemd)・既存パイプラインは不変

### Project Structure (structure.md)

- Actuator は Spring Boot の横断機能で特定 feature に属さないため、新しい feature パッケージは作らない。変更は `build.gradle.kts`(依存)・`application.yml`(management 設定)・`shared/config/AccessJwtFilter.kt`(除外パス)に閉じる
- インフラ成果物は `docker/` 配下に置く(`docker/prometheus/prometheus.yml`・`docker/grafana/provisioning/`)

## Code Reuse Analysis

- **spring-boot-starter-actuator + micrometer-registry-prometheus**: `http.server.requests`(エンドポイント別のカウント + タイマー)、JVM(ヒープ・GC・スレッド)、HikariCP(`hikaricp_connections_*`)が**自動計装**で載る。エンドポイント計測のための自前コードは不要
- **spring-kafka のリスナータイマー(`spring.kafka.listener`)**: Kafka はリスナーコンテナが自動登録するタイマーで観測する。consumer クライアントメトリクス(`kafka_consumer_*`)は本コードベースでは載らないため使わない(下記「Kafka メトリクスの方式」参照)
- **shared/config/AccessJwtConfig / AccessJwtFilter**: 既存の任意ハードニング機構をそのまま使い、フィルタに除外パスだけ足す(下記)
- **docker-compose.yml の既存イディオム**: ループバック限定 bind(kafka-ui)・named volume(kafka-data / postgres-data)・`docker/.env` からの秘密注入(cloudflared)を踏襲

## アプリ側の設計

### 依存と設定

`build.gradle.kts`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
runtimeOnly("io.micrometer:micrometer-registry-prometheus")
```

`application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus   # 最小限のみ。env/beans 等は晒さない(要件 2)
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true   # histogram_quantile() で p95/p99 を出すためのバケット公開(要件 1.3)
```

- expose の既定は Spring Boot 3 では `health` のみだが、明示的に `health,prometheus` を書いて「これ以外は出さない」を設定として固定する
- percentiles-histogram は「Prometheus 側でクエリ時に分位数を計算する」方式。アプリ側で分位数を焼き込む `percentiles` 設定は使わない(集約・時間範囲の変更に強いため)

### AccessJwtFilter との共存(設計判断)

**現状の挙動**(`shared/config/` を確認した正確な記述):

- `AccessJwtConfig` は `@ConditionalOnProperty(name = ["rss-watch.access.aud"])` で、`rss-watch.access.aud` 設定時のみ `FilterRegistrationBean` を登録する(未設定なら無効。**現状本番未適用**の任意ハードニング)
- 登録時は `addUrlPatterns("/*")` + `Ordered.HIGHEST_PRECEDENCE` で**全リクエスト**が対象。`Cf-Access-Jwt-Assertion` ヘッダの欠落・検証失敗は 401
- したがって有効化した環境では、localhost の Prometheus コンテナからの scrape(Access を経由しないため当該ヘッダを持たない)は **401 になり観測が止まる**

**設計判断: `/actuator/health` と `/actuator/prometheus` の 2 パスのみをフィルタ対象外にする。**

- 方式: `AccessJwtFilter` に `OncePerRequestFilter#shouldNotFilter` を override し、`request.requestURI` が上記 2 つに一致するときスキップする(`FilterRegistrationBean.addUrlPatterns` は除外指定を持たないため、除外はフィルタ側で行うのが素直)
- `/actuator/**` 全体ではなく 2 パスに限定する理由: expose を将来広げた場合(例: 一時的な `loggers`)にまで無認証を波及させないため。除外リストと expose リストを同じ「最小限」で揃える
- 注記: exact match のため、将来 liveness/readiness プローブのサブパス(`/actuator/health/liveness` 等)を使う場合はそれらは除外されない(`/actuator/health/**` を意図的にカバーしない設計判断。現状サブパスは使っておらず実害なし。導入時に除外リストへ明示的に追加する)
- セキュリティ評価: この除外で無認証になるのは「メトリクスと死活」のみで、環境変数や設定は expose していない(要件 2)。到達経路は (a) localhost / LAN の `:8080` 直アクセス、(b) Cloudflare トンネル経由 — (b) はエッジの Access 認証が先に立つため保護は維持される。(a) はハードニング前の信頼レベル(自宅 LAN 内は許容)に、この 2 パスだけ戻ることを意味し、個人運用として許容する
- 代替案(不採用): Prometheus に Access のサービストークンを持たせてヘッダ付き scrape する案は、トークン管理と Cloudflare 側設定が増える割に、守る対象がメトリクスのみでは見合わない

### Kafka メトリクスの方式(設計判断)

**前提(コード確認)**: `shared/config/KafkaConfig.kt` は `DefaultKafkaConsumerFactory` をコンテナファクトリ内でインライン new している。Boot の `KafkaMetricsAutoConfiguration` が提供する `DefaultKafkaConsumerFactoryCustomizer`(`MicrometerConsumerListener` の付与)は、**Boot 自動構成の `kafkaConsumerFactory` Bean の生成時にのみ**適用される(`KafkaAutoConfiguration#kafkaConsumerFactory` が ObjectProvider 経由で customize を呼ぶことを javap で確認)。本コードベースの自前 factory には適用されないため、consumer クライアントメトリクス(`kafka_consumer_fetch_manager_records_consumed_total` 等の `kafka_consumer_*`)は**登録されない**。

**採用: spring-kafka のリスナータイマー `spring.kafka.listener`(Prometheus 名 `spring_kafka_listener_seconds_*`)。** consumer factory の作り方に依存せず、リスナーコンテナ自身が登録するタイマーで観測する。成立条件は spring-kafka 3.3.10(本プロジェクトの解決バージョン)のクラスを javap で確認済み:

- `ContainerProperties` の `micrometerEnabled` は既定 true
- `KafkaMessageListenerContainer$ListenerConsumer#obtainMicrometerHolder()` は「micrometer-core がクラスパスにある(`KafkaUtils.MICROMETER_PRESENT` = `io.micrometer.core.instrument.MeterRegistry` の存在チェック)+ `micrometerEnabled` + observation 無効(既定)」のとき `MicrometerHolder` を生成し、タイマー名 `spring.kafka.listener`(タグ: `name` = リスナーコンテナの Bean 名、`result` = success/failure、`exception`)を登録する
- `MicrometerHolder` は ApplicationContext から `getBeanProvider(MeterRegistry).getIfUnique()` で MeterRegistry を解決する。ApplicationContext は `AbstractKafkaListenerContainerFactory`(ApplicationContextAware)が `initializeContainer` で各コンテナへ引き渡すため、**consumer factory を自前 new していても、コンテナファクトリが `@Bean` であれば成立する**(KafkaConfig の 2 ファクトリはどちらも `@Bean`)

したがって NFR「アプリ側変更は依存追加 + `application.yml` + AccessJwtFilter 除外のみ」を維持したまま Kafka の観測ができる。なお sink はバッチリスナーのため 1 回のタイマー記録 = 1 バッチ処理であり、パネルの「処理レート」はレコード数ではなく**リスナー呼び出し数**のレートになる。consumer ラグや records-consumed 等のクライアントメトリクスが必要になった場合は、consumer factory の Bean 化 + `MicrometerConsumerListener` 付与を別 spec で検討する(本 spec のスコープ外)。

## インフラ側の設計

### Prometheus(docker/docker-compose.yml + docker/prometheus/prometheus.yml)

```yaml
  prometheus:
    image: prom/prometheus:<pinned-version>
    container_name: rss-watch-prometheus   # 既存サービス(rss-watch-kafka 等)の命名イディオムに合わせる
    ports:
      - "127.0.0.1:9090:9090"        # kafka-ui と同方針: LAN に露出しない
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.retention.time=90d   # 改善デプロイ前後の比較に足る保持(要件 4.2)
    extra_hosts:
      - "host.docker.internal:host-gateway" # コンテナ → ホストの :8080 への到達手段(下記)
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    restart: unless-stopped
```

`docker/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: rss-watch
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

**scrape target の設計判断(要件 4.5)**: アプリはコンテナではなく**ホスト上**(本番: systemd、開発: bootRun)で `:8080` を listen するため、Prometheus コンテナから `localhost:8080` では届かない。到達方式は 2 案:

| 方式 | 仕組み | 評価 |
|---|---|---|
| **`extra_hosts: host.docker.internal:host-gateway`(採用)** | Docker がホスト側 IP を `host.docker.internal` に解決する | Linux 本番・macOS 開発の**両方で同じ設定のまま動く**。コンテナのネットワーク分離(ループバック限定 bind)も維持される |
| `network_mode: host`(cloudflared が採用している方式) | コンテナがホストのネットワーク名前空間を共有し `localhost:8080` が直接届く | **Linux ホスト前提**(macOS/Windows の Docker Desktop では同挙動にならない、と docs/public-access.md にも明記済み)。さらに ports 指定が無効になりループバック限定 bind の表現もできない |

cloudflared は「公開時のみ・Linux 前提」と割り切った常駐なので host モードでよいが、Prometheus は開発機(macOS)でも同じ compose で動かしたいため `host.docker.internal` 方式を採る。

### Grafana(docker/docker-compose.yml + docker/grafana/provisioning/)

```yaml
  grafana:
    image: grafana/grafana:<pinned-version>
    container_name: rss-watch-grafana
    ports:
      - "127.0.0.1:3001:3000"   # 自宅サーバーは homepage が :3000 使用中のため 3001 に避ける
    environment:
      GF_SERVER_ROOT_URL: ${GRAFANA_ROOT_URL:-http://localhost:3001}  # 公開時は https://grafana.<ドメイン> を docker/.env で注入
      GF_AUTH_ANONYMOUS_ENABLED: "true"       # 閲覧はログイン不要(Viewer)。書き込みは admin ログイン
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}    # docker/.env から注入(コミットしない)
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
    restart: unless-stopped
```

- 匿名 Viewer を許すのは、到達経路がループバック + Access 保護トンネルに限定されているため(Access で人を絞り、Grafana ログインを二重に求めない)。編集(admin)だけパスワードで守る
- `docker/.env.example` に `GRAFANA_ADMIN_PASSWORD` / `GRAFANA_ROOT_URL` の行を追記する
- **注意(admin パスワードの焼き付き)**: `GF_SECURITY_ADMIN_PASSWORD` は **grafana-data volume の初回初期化時にのみ**反映され、あとから `docker/.env` を変えて再起動しても変わらない。したがって**初回 `up -d` の前に `docker/.env` を用意しておく**こと。初回以降に変更したい場合は `docker compose exec grafana grafana cli admin reset-admin-password <新パスワード>` でリセットする(新形式。旧形式の `grafana-cli` は同コマンドへの deprecated ラッパーとして残っているだけなので新形式を使う。この運用注意は Task 4 の docs にも記載する)

**provisioning 構成**(手動セットアップゼロでリポジトリ管理。要件 5.2):

```
docker/grafana/provisioning/
├── datasources/prometheus.yml        # url: http://prometheus:9090(compose 内 DNS)
└── dashboards/
    ├── dashboards.yml                # このディレクトリの JSON を自動読み込み
    └── rss-watch.json                # ダッシュボード本体(コミット対象)
```

### ダッシュボード(rss-watch.json)のパネル構成

| パネル | クエリの骨子 |
|---|---|
| エンドポイント別レイテンシ p50/p95/p99 | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`(0.5 / 0.99 も同様。`uri` 変数で `/api/report` に絞れるようにする) |
| リクエストレート | `sum by (uri) (rate(http_server_requests_seconds_count[5m]))` |
| エラー率 | `sum(rate(...{status=~"5.."}[5m])) / sum(rate(...[5m]))`(PromQL の `or` は左辺が**空ベクトル**のときだけ右辺を採用する。5xx が一度も発生していない期間は分子の系列自体が存在せず空になりパネルが欠けるため、`or vector(0)` を添えて 0 として描画する。なお 0/0 = NaN は非空なので `or` では救えないが、分母は Prometheus 自身の scrape リクエストが常に載るため実質 0 にならない) |
| JVM ヒープ | `jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes{area="heap"}` |
| HikariCP | `hikaricp_connections_active` / `hikaricp_connections_pending` / `hikaricp_connections_max` |
| Kafka リスナー | `sum by (name) (rate(spring_kafka_listener_seconds_count[5m]))`(リスナー別の処理レート)と `sum by (name) (rate(spring_kafka_listener_seconds_sum[5m])) / sum by (name) (rate(spring_kafka_listener_seconds_count[5m]))`(平均処理時間。sink はバッチリスナーのため 1 呼び出し = 1 バッチ)。要件 1.4 の Kafka メトリクスをダッシュボードから参照できることの担保。実地確認は Task 3 の完了条件 |

- 時間範囲を広げれば同一パネルで改善デプロイ前後の比較ができる(主目的のユースケース)。デプロイ時刻の注釈(annotation)は手動運用で足りるため provisioning には含めない
- `/actuator/prometheus` の URI 自体もタグに載るが、ダッシュボードの `uri` 変数で除外できるためアプリ側でのフィルタはしない
- **`/api/stream`(SSE)の除外**: `/api/stream` は長寿命の SSE 接続で、`http.server.requests` には**接続が閉じた時点で接続寿命ぶんの duration** が記録される(数分〜数時間オーダー)。そのままではレイテンシパネルの p50/p95/p99 を大きく汚すため、レイテンシパネルのクエリ(または `uri` 変数の既定値)から `/api/stream` を除外する

## 外部公開(要件 6)

既存 cloudflared トンネルの Public Hostname に `grafana.<ドメイン>` → `http://localhost:3001` を追加し、Access アプリ(rss-watch と同じ許可メールポリシー)で保護する。cloudflared は `network_mode: host` で動いているため、ホストにループバック bind された `:3001` にそのまま到達する。Cloudflare ダッシュボード操作のみでリポジトリ成果物はなく(ユーザー作業)、手順は `docs/public-access.md` に追記する。

## Error Handling

### Error Scenarios

1. **アプリ停止中の scrape 失敗**
   - **Handling:** Prometheus が target を down と記録するだけ(`up == 0`)。再起動後に自動復帰
   - **User Impact:** 停止期間のメトリクスが欠けるのみ
2. **Prometheus / Grafana コンテナの停止**
   - **Handling:** アプリ本体は無影響(pull 型)。`restart: unless-stopped` で自動復帰。retention 内のデータは volume に残る
   - **User Impact:** その間ダッシュボードが見られない/収集が欠ける
3. **AccessJwtFilter 有効化環境での scrape**
   - **Handling:** `/actuator/prometheus` は除外パスのため 401 にならない(設計判断どおり)
   - **User Impact:** なし

## Testing Strategy

### Unit Testing(TDD。CLAUDE.md の Red → Green → Refactor に従う)

- **フルコンテキストテストの隔離前提**: `@SpringBootTest` 系テストは既存 `src/test/kotlin/dev/rockyh/rsswatch/RssWatchApplicationTest.kt` と同じ隔離を踏襲する
  - `@Import(PostgresTestConfiguration::class)` で Testcontainers の共有 PostgreSQL コンテナに接続する
  - `spring.kafka.bootstrap-servers=localhost:1`(到達不能ポート)に上書きし、ローカルの実ブローカー(localhost:9092)に group join して実メッセージを消費してしまう事故を防ぐ(接続失敗はバックグラウンドリトライになるだけで、コンテキスト起動は失敗しない)
  - `rss-watch.fetch.initial-delay-ms=3600000` で、テスト中に `@Scheduled` の巡回(実フィードへのアクセス)が走らないようにする
- **メトリクス公開**: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + TestRestTemplate 等で
  - `GET /actuator/prometheus` が 200 で、任意のリクエスト実行後に `http_server_requests_seconds` を含むこと(要件 1.1/1.2)
  - percentiles-histogram 有効により `http_server_requests_seconds_bucket` が含まれること(要件 1.3)
  - 自動計装メトリクスの存在確認: 出力に `jvm_memory_used_bytes` と `hikaricp_connections` 系のメーターが含まれること(要件 1.4)。Kafka のリスナータイマー(`spring_kafka_listener_seconds`)はリスナーコンテナ起動時に登録されるが、テストはブローカー到達不能設定(localhost:1)でコンテナを起動しておりメーターの有無がコンテナ起動状況に依存し得るため、テストでの断定は避け、Task 3 の実地確認(Kafka リスナーパネルの描画)で確認する
  - `GET /actuator/health` が 200(要件 1.5)。health は DataSource ヘルスチェックを含むため、この 200 は Postgres コンテナ(PostgresTestConfiguration)への接続が UP であることが前提
  - `GET /actuator/env`・`/actuator/beans` が 404(expose 最小限の仕様化。要件 2.2)
- **AccessJwtFilter の除外**: `rss-watch.access.aud` / `team-domain` を設定したコンテキストで
  - ヘッダなしの `GET /actuator/prometheus`・`/actuator/health` が 401 にならないこと(要件 3.1)
  - ヘッダなしの `GET /api/report` が 401 のままであること(要件 3.2)
  - `shouldNotFilter` の単体テスト(パス一致 / 不一致)でも仕様を固定する
- 既存テスト(AccessJwtFilter / AccessJwtConfig の既存分含む)が green のまま(要件 3.3)

### Integration Testing

- compose 側(prometheus.yml・provisioning)は宣言的設定のため自動テスト対象外。`docker compose config` が通ること(構文検証)を確認する

### End-to-End Testing(手動)

- `docker compose up -d` + `./gradlew bootRun` 後、`http://localhost:9090/targets` で rss-watch target が UP、`http://localhost:3001` でダッシュボードにレイテンシが描画されること(`/api/report` を数回叩いて確認)
- 本番(Linux + systemd)でも同一 compose で target UP になること(`host.docker.internal` 方式の実地確認)
