# Design Document

## Overview

アプリに Micrometer Tracing(OTel ブリッジ)+ OTLP エクスポータと JDBC 観測(datasource-micrometer)を足し、`docker/docker-compose.yml` に Grafana Tempo(トレース保存)を追加する。閲覧は既存 Grafana に Tempo datasource を provisioning で足し、Explore(TraceQL)でウォーターフォール図を見る。アプリ側はプロダクションコードの変更なし(任意の Kafka observation を除く)で、大部分が依存追加と設定・インフラ定義になる — observability spec と同じ構図。

```
ホスト上のアプリ(systemd / bootRun :8080)
  │ OTLP/HTTP push(バッチ送信)
  ▼
Tempo (127.0.0.1:4318 受信 / API :3200 は非公開) ←─ query(compose 内 DNS tempo:3200)── Grafana (127.0.0.1:3001)
```

メトリクス(Prometheus)が pull 型なのに対しトレースは push 型だが、送信はバックグラウンドのバッチ処理で、Tempo が止まってもアプリは無影響(スパンを捨ててエラーログを出すだけ)という点で「観測系の障害がアプリに波及しない」性質は変わらない。

## Steering Document Alignment

### Technical Standards (tech.md)

- 「メトリクスは Prometheus、アプリは公開するだけ」に続けて「トレースは Tempo、アプリは送るだけ」。観測バックエンドの障害はアプリ本体に波及しない
- 運用ツールを compose に相乗りさせる方針(kafka-ui / Prometheus / Grafana と同格)。デプロイ方針(fat jar + systemd)・既存パイプラインは不変
- 閲覧 UI は既存 Grafana に集約し、公開ホスト名・Access アプリを増やさない

### Project Structure (structure.md)

- トレーシングは Spring Boot の横断機能で特定 feature に属さないため、新しい feature パッケージは作らない。変更は `build.gradle.kts`(依存)・`application.yml`(management 設定)に閉じる(任意 Task 3 のみ `shared/config/KafkaConfig.kt`)
- インフラ成果物は `docker/` 配下に置く(`docker/tempo/tempo.yml`・`docker/grafana/provisioning/datasources/`)

## Code Reuse Analysis

- **Micrometer Observation(observability spec で導入済みの基盤)**: Spring MVC の `http.server.requests` は既に Observation として計測されている。Micrometer Tracing のブリッジを足すと、**同じ Observation からメトリクスに加えてスパンも生成される**(計装の二重化なし)。エンドポイント計測のための自前コードは不要
- **自動構成の RestClient.Builder**: ClaudeSummarizer / DiscordWebhookClient は自動構成の builder を使っているため、アウトバウンド HTTP(`http.client.requests`)のスパンも追加コードなしで載る(ダイジェスト配信の Claude / Discord 呼び出しの所要時間が見える。おまけであり要件ではない)
- **docker-compose.yml の既存イディオム**: タグ固定イメージ・`container_name: rss-watch-*`・ループバック限定 bind・named volume・設定ファイルの `ro` マウントを踏襲
- **Grafana provisioning(observability spec で導入済み)**: `docker/grafana/provisioning/datasources/` に YAML を 1 枚足すだけで、手動セットアップなしで Tempo datasource が使える

## アプリ側の設計

### 依存と設定

`build.gradle.kts`(バージョンはすべて Spring Boot 3.5.6 の dependency management が解決する。micrometer-tracing 1.5.4 / opentelemetry 1.49.0 を POM で確認済み):

```kotlin
// トレース生成(Observation → OTel スパンへのブリッジ)と OTLP 送信
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
// クエリ単位の SQL スパン(本 spec の核心価値)。Boot の BOM 管理外なのでバージョン明示
implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:1.1.1")
```

- datasource-micrometer-spring-boot は Maven Central の現行最新 1.1.1 を想定。Boot 3.5 との互換は実装時に公式 compatibility 情報で確認する(合わなければ対応バージョンへ調整)

`application.yml`:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # 既定 0.1。全量にする理由は下記「サンプリング」
  otlp:
    tracing:
      endpoint: http://127.0.0.1:4318/v1/traces   # ローカルの Tempo(OTLP/HTTP)
```

- **endpoint は明示必須**: Spring Boot 3.5 の OTLP トレーシング自動構成は `@ConditionalOnProperty("management.otlp.tracing.endpoint")` で、未設定だとエクスポータ Bean 自体が作られない(actuator-autoconfigure 3.5.6 を javap で確認済み。「デフォルト localhost に送る」挙動は**ない**)
- アプリはホスト上(systemd / bootRun)、Tempo はループバック bind のコンテナなので、本番・開発とも `127.0.0.1:4318` で到達する(環境差し替え不要。必要になれば relaxed binding の環境変数 `MANAGEMENT_OTLP_TRACING_ENDPOINT` で上書き可能)
- トランスポートは既定の `http`(OTLP/HTTP)。gRPC(4317)は使わない(依存・ポートが増えるだけで利点がない)
- service name は `spring.application.name`(`tech-and-job-rss-reader`)が使われる。actuator の expose(`health,prometheus`)は変更しない

### バックエンド選定: Tempo(設計判断)

| 候補 | 概要 | 評価 |
|---|---|---|
| **Grafana Tempo(採用)** | Grafana 製のトレースバックエンド。TraceQL で検索、閲覧は Grafana 本体 | **既存 Grafana に datasource を足すだけで閲覧でき、UI・公開ホスト名・Access アプリが増えない**。compose 追加は 1 コンテナ + 設定 1 枚で最小。ローカル filesystem バックエンド + 保持期間設定が単純。将来 exemplars をやるなら Grafana ↔ Tempo の組み合わせが最短 |
| Jaeger(all-in-one) | CNCF graduated の定番。1 コンテナで UI(:16686)込み | 機能的には十分だが、**専用 UI がもう 1 枚増える**(ループバック限定にすると外から見るには公開ホスト名 + Access アプリの追加が要る)。Grafana の Jaeger datasource 経由で見るなら結局 Grafana に集約することになり、それなら Grafana 純正で組み合わせ実績の厚い Tempo でよい。CNCF 直系という学習的な魅力はあるが、本アプリの CNCF 学習は Kafka で担っており、観測系は運用の楽さを優先する |
| Zipkin | 最古参で最小構成 | 保持がメモリ既定(永続化は別途 DB)で、TraceQL 相当の検索性・Grafana との統合の深さで見劣り。新規採用の理由がない |

- OTel Collector(アプリと Tempo の間に挟む中継)は置かない。送信元がこのアプリ 1 つで、加工・ルーティングの必要がないため、直接 OTLP で Tempo に送る

### SQL スパンの方式(設計判断 — 本 spec の核心価値)

**課題**: Micrometer Tracing だけでは HTTP サーバースパン 1 個しか出ず、「リクエストのどこで時間を食っているか」が分からない。kuery-client は spring-data-jdbc ベースで最終的に JDBC(DataSource)を通るため、**DataSource をプロキシして観測するのが計装ポイントとして最適**(kuery-client にも各 Repository にも手を入れない)。

**採用: `datasource-micrometer-spring-boot`(net.ttddyy.observation)。** Spring Boot 3 向けの JDBC 観測ライブラリで、starter が DataSource Bean を自動でプロキシし、コネクション取得・クエリ実行・ResultSet 取得を Observation として計測する。Micrometer Tracing のブリッジがあるため、これがそのまま**クエリ単位のスパン(SQL 文つき)**になる。

- ウォーターフォールの読み方: HTTP サーバースパンの子に各 SQL スパンが並ぶ。「最後の SQL 完了〜HTTP スパン終了」の差分がレスポンス組み立て + 書き出し(専用スパンは作らない。差分で読めるため十分)。N+1 は同型 SQL スパンの繰り返しとして視認できる
- スパンには SQL 文が入るが、バインドパラメータ値は既定では入らない(既定挙動であることは実装時に実出力で検証し、もし入る場合は無効化設定を入れる)
- 観測の粒度は既定(CONNECTION / QUERY / FETCH)から始め、スパンが冗長ならプロパティ(`jdbc.includes`)で QUERY 中心に絞る — 細部のプロパティ名は実装時にライブラリの README で確認する
- 代替案(不採用): OTel Java agent(`-javaagent`)は JDBC 含む広範な自動計装が得られるが、systemd unit・bootRun・テスト実行のすべてに agent 配布と起動引数の管理が増え、Micrometer Observation(Boot 標準の観測基盤)と二重計装になる。Boot 3 標準路線(observability spec と同じ基盤の延長)で揃える

### サンプリング(設計判断)

`management.tracing.sampling.probability: 1.0`(全量)。既定の 0.1 は「本番の大量トラフィックでコストを抑える」ための値であり、本アプリには当てはまらない:

- トラフィックが自宅規模(自分 + スケジューラ)で、保存コストが無視できる
- 用途が性能調査なので、「遅かったあのリクエスト」がサンプリングで欠けていては意味がない。全量なら Grafana で見たメトリクスの外れ値に対応するトレースが必ず存在する

### トレース内容の機微情報

トレースにはリクエスト URI・HTTP メソッド・ステータス・SQL 文(バインド値なし)が入る。このアプリは個人利用で、URI・SQL に第三者の個人情報や秘密は含まれない。閲覧経路も Tempo がループバック限定 bind、Grafana が従来どおりループバック + Cloudflare Access 保護のトンネルに限定されるため、追加のマスキングは行わない。

### AccessJwtFilter との関係

AccessJwtFilter は**インバウンド** HTTP リクエストの認証であり、トレース送信(アプリ → Tempo の**アウトバウンド** OTLP)には一切関与しない。Tempo はアプリの HTTP エンドポイントを呼ばないため、observability spec のような除外パス追加も不要。本 spec でフィルタは変更しない。

### Kafka observation(任意 — Requirement 5)

**前提(コード確認)**: `shared/config/KafkaConfig.kt` は `ConcurrentKafkaListenerContainerFactory` を自前 `@Bean` で組んでいる。Boot のプロパティ `spring.kafka.listener.observation-enabled`(既定 false。Boot 3.5.6 の configuration metadata で確認済み)は**自動構成のコンテナファクトリにしか効かない**(observability spec で「自動計装は自動構成に乗っているときだけ働く」と学んだのと同じ構図)。有効化する場合はファクトリ側で直接設定する:

```kotlin
factory.containerProperties.isObservationEnabled = true   // liveKafkaListenerContainerFactory に追加
```

実施する場合に把握しておくべき制約(いずれも実装時に実挙動で最終確認する):

- **sink には効かない**: spring-kafka の observation はレコードリスナー単位で、**バッチリスナーはサポート外**(spring-kafka リファレンスの Observability に明記)。スパンが付くのは live のみ
- **既存メトリクスのタグが変わる**: observability spec の設計どおり、`spring.kafka.listener` タイマー(タグ `name`/`result`/`exception`)は「observation 無効」のときに `MicrometerHolder` が登録するもの。observation を有効化するとタイマーは observation 由来に置き換わり、タグ体系が `spring.kafka.listener.id` + `messaging.*` 系に変わる(spring-kafka 3.3.10 の `KafkaListenerObservation$ListenerLowCardinalityTags` を javap で確認: LISTENER_ID / MESSAGING_SYSTEM / MESSAGING_OPERATION / MESSAGING_SOURCE_NAME / MESSAGING_SOURCE_KIND / MESSAGING_CONSUMER_GROUP)。**Grafana「Kafka リスナー」パネルの `sum by (name)` が壊れる**ため、有効化するならパネルのクエリを追随修正する。live のみ有効化なら sink 側のタイマーは従来のまま残る点にも注意(パネルが新旧タグ混在になる)
- producer 側(`stringKafkaTemplate`)も自前組みのため、送るなら `template.setObservationEnabled(true)` が別途必要。ただし producer → consumer のトレース連結(ヘッダ伝播)まで欲しくなったら別 spec で扱う

HTTP + SQL が主目的であり、このタグ互換の手間が見合わないと判断したら見送る(Requirement 5 は任意)。

## インフラ側の設計

### Tempo(docker/docker-compose.yml + docker/tempo/tempo.yml)

```yaml
  # トレースの受信(OTLP)・保存・検索。閲覧は Grafana(Tempo datasource)から行う
  tempo:
    image: grafana/tempo:2.10.7            # タグ固定(Docker Hub の現行安定版。実装時に最新 patch を確認)
    container_name: rss-watch-tempo
    command: ["-config.file=/etc/tempo/tempo.yml"]
    ports:
      # OTLP/HTTP 受信。ホスト上のアプリ(systemd / bootRun)から届けばよいのでループバック限定 bind
      - "127.0.0.1:4318:4318"
      # Tempo API(:3200)は publish しない(Grafana から compose 内 DNS tempo:3200 で届く)
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
      - tempo-data:/var/lib/tempo
    restart: unless-stopped
```

`docker/tempo/tempo.yml`(骨子。細部のキーは実装時に採用バージョンの公式リファレンスで確認する):

```yaml
server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318   # Tempo 2.7+ は受信の既定 bind が localhost に変わったため明示(コンテナ外=アプリからの受信に必要)
storage:
  trace:
    backend: local                 # 単一ノードの filesystem バックエンドで十分(S3 等は不要)
    local:
      path: /var/lib/tempo/blocks
    wal:
      path: /var/lib/tempo/wal
compactor:
  compaction:
    block_retention: 336h          # 14 日(要件 3.3)。トレースは調査用途なので Prometheus の 90d は不要
```

- 到達経路の整理: アプリ(ホスト)→ Tempo はホストに bind された `127.0.0.1:4318`。Grafana(コンテナ)→ Tempo は compose 内 DNS `tempo:3200`。Prometheus の `host.docker.internal`(コンテナ → ホスト)とは向きが逆で、こちらは通常の port publish で足りる
- ポートは 4318 のみ追加で、既存(8080 / 8081 / 9090 / 9092 / 3001 / 5432、ホストの homepage :3000)と衝突しない(要件 3.5)
- 実装時の確認事項: コンテナの実行ユーザーと named volume の書き込み権限(公式イメージは非 root 実行のため、`/var/lib/tempo` に書けない場合は user 指定や初期化を調整する)

### Grafana datasource(docker/grafana/provisioning/datasources/tempo.yml)

```yaml
apiVersion: 1
datasources:
  - name: Tempo
    uid: tempo          # 既存 prometheus.yml と同じく固定 uid
    type: tempo
    access: proxy
    url: http://tempo:3200   # compose 内 DNS(Grafana コンテナ → Tempo コンテナ)
    editable: false
```

- 既存の `prometheus.yml`(isDefault: true)はそのまま。Tempo は default にしない
- 閲覧導線: Grafana の Explore で datasource に Tempo を選び、TraceQL で検索する(例: `{resource.service.name="tech-and-job-rss-reader" && span:duration > 500ms}`、`{name =~ "/api/report.*"}` 等)。手順と例は docs に記載する(Task 4)。既存ダッシュボードからのリンクや exemplars(メトリクスの点 → トレースへのジャンプ)は**スコープ外**とし、将来課題として docs に一言残す

## Error Handling

### Error Scenarios

1. **Tempo 停止中・未起動でのアプリ稼働(ローカル開発で compose を上げていない場合を含む)**
   - **Handling:** OTel エクスポータのバッチ送信が失敗し、スパンは破棄されてエラーログが出る。リクエスト処理・パイプラインは無影響(送信は非同期バッチで、リクエストスレッドをブロックしない)
   - **User Impact:** その間のトレースが残らない + ログにエクスポート失敗が周期的に出る(compose を上げれば解消。恒常的に Tempo なしで動かしたい環境では `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false` で送信だけ止められる)
2. **Tempo コンテナの障害・再起動**
   - **Handling:** `restart: unless-stopped` で自動復帰。保持期間内のデータは named volume に残る
   - **User Impact:** 停止中のトレースが欠けるのみ
3. **スパンの取りこぼし(バースト時)**
   - **Handling:** エクスポータのキュー(既定 max-queue-size 2048)超過分はドロップされる。自宅規模のトラフィックでは実質発生しない
   - **User Impact:** 極端なバースト時に一部トレースが不完全になる可能性(許容)

## Testing Strategy

### Unit Testing(TDD。CLAUDE.md の Red → Green → Refactor に従う)

- **テストは実 Tempo に送信しない(仕組みの整理)**: Spring Boot のテスト基盤(spring-boot-test-autoconfigure の `ObservabilityContextCustomizerFactory`)は、`@AutoConfigureObservability` が付いていないテストに `management.tracing.enabled=false` を自動適用する(3.5.6 の class を javap で確認済み)。したがって**既存のフルコンテキストテスト 6 個(RssWatchApplicationTest / SinkConsumerIntegrationTest / LiveConsumerIntegrationTest / NotifyFeatureToggleTest / ActuatorAccessJwtTest / ActuatorEndpointsTest)は無変更のままトレース送信を試みず**、OTLP 接続失敗のエラーログも出ない。application.yml に endpoint を書いてもテストには影響しない
- **トレース配線のテスト(新規)**: `@SpringBootTest` + `@AutoConfigureObservability(metrics = false)`(tracing のみ有効化)+ `management.otlp.tracing.export.enabled=false` を上書きしたコンテキストで
  - `io.micrometer.tracing.Tracer` Bean が存在する(ブリッジの配線が生きている)こと
  - `management.tracing.sampling.probability` が 1.0 で束縛されていること(全量サンプリングの仕様化)
  - DataSource Bean が datasource-micrometer によりプロキシされていること(JDBC 観測の配線が生きていること。アサート方法 — プロキシ型の検査等 — は実装時にライブラリの提供 API を確認して決める)
  - `export.enabled=false` でエクスポータ Bean が作られない挙動(`@ConditionalOnEnabledTracing` 相当)は Boot の実装依存なので、テストが OTLP 接続エラーを出さないことを実行ログで確認する(出る場合は endpoint をテスト時のみ未設定に上書きする方式へ切り替える — endpoint 未設定ならエクスポータ Bean 自体が作られないことは確認済みのため確実に効く)
  - 隔離設定(PostgresTestConfiguration・`spring.kafka.bootstrap-servers=localhost:1`・`rss-watch.fetch.initial-delay-ms=3600000`)は既存フルコンテキストテストの前提を踏襲する
- **スパンの中身(SQL 文が入る・バインド値が入らない等)は単体テストで断定しない**: エクスポート形式の検証は OTel SDK の in-memory exporter を組む大掛かりなテストになる割に、既定挙動の追認にしかならない。E2E(下記)の実地確認に回す
- 既存テストがすべて green のまま(デグレなし)

### Integration Testing

- compose 側(tempo.yml・datasource provisioning)は宣言的設定のため自動テスト対象外。`docker compose config` が通ること(構文検証)を確認する

### End-to-End Testing(手動)

- `docker compose up -d` + `./gradlew bootRun` 後、`/api/report` を数回叩き、Grafana Explore(Tempo datasource)の TraceQL 検索でトレースがヒットし、ウォーターフォールに「HTTP サーバースパン + 子の SQL スパン(SQL 文つき)」が並ぶこと
- SQL スパンにバインドパラメータ値が含まれないこと(要件 2.4 の実地検証)
- Tempo を止めてもアプリが正常応答し続けること(エラーログのみでリクエスト影響なし)
- 本番(Linux + systemd)でも同一 compose でトレースが届くこと
