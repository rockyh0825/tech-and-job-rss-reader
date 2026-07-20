# Tasks Document

アプリ側変更(Task 1)は TDD(Red → Green → Refactor)で進める(CLAUDE.md 準拠。テストが先・実装が後)。Task 2〜3 は宣言的なインフラ定義のため自動テストは持たず、完了条件を手動確認で定義する。

- [ ] 1. Actuator + Micrometer 導入と AccessJwtFilter の actuator 除外(TDD)
  - File: build.gradle.kts(spring-boot-starter-actuator / micrometer-registry-prometheus 追加)、src/main/resources/application.yml(management 設定)、src/main/kotlin/dev/rockyh/rsswatch/shared/config/AccessJwtFilter.kt(shouldNotFilter 追加)
  - Test: `/actuator/prometheus` が 200 で `http_server_requests_seconds`(+ percentiles-histogram の `_bucket`)を含む、`/actuator/health` が 200、`/actuator/env` が 404(expose 最小限)、`rss-watch.access.aud` 設定時にヘッダなしでも `/actuator/prometheus`・`/actuator/health` は 401 にならず `/api/report` は 401 のまま、shouldNotFilter のパス一致/不一致
  - 完了条件: 上記テストがすべて green で、既存テスト(AccessJwtFilter/Config 含む)もデグレなし。expose は `health,prometheus` のみ
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 3.1, 3.2, 3.3_

- [ ] 2. docker-compose に prometheus サービス + scrape 設定を追加
  - File: docker/docker-compose.yml(prometheus サービス + prometheus-data volume)、docker/prometheus/prometheus.yml(新規: scrape_interval 15s・metrics_path /actuator/prometheus・target host.docker.internal:8080)
  - タグ固定イメージ・`127.0.0.1:9090:9090`(ループバック限定)・`--storage.tsdb.retention.time=90d`・`extra_hosts: ["host.docker.internal:host-gateway"]`(design の scrape target 設計判断参照)
  - 完了条件: `docker compose config` が通り、`docker compose up -d` + アプリ起動後に `http://localhost:9090/targets` で rss-watch target が UP(macOS 開発機で確認。Linux 本番は Task 4 のデプロイ時に実地確認)
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 3. Grafana サービス + provisioning(datasource・ダッシュボード JSON)を追加
  - File: docker/docker-compose.yml(grafana サービス + grafana-data volume)、docker/grafana/provisioning/datasources/prometheus.yml、docker/grafana/provisioning/dashboards/dashboards.yml、docker/grafana/provisioning/dashboards/rss-watch.json、docker/.env.example(GRAFANA_ADMIN_PASSWORD / GRAFANA_ROOT_URL 追記)
  - `127.0.0.1:3001:3000`(homepage が :3000 使用中のため)・`GF_AUTH_ANONYMOUS_ENABLED=true`(Viewer)・`GF_SERVER_ROOT_URL` と admin パスワードは docker/.env 注入。ダッシュボードはエンドポイント別レイテンシ(p50/p95/p99)・リクエストレート・エラー率・JVM ヒープ・HikariCP のパネル(design のクエリ骨子参照)
  - 完了条件: `docker compose up -d` 直後に `http://localhost:3001` へ匿名(Viewer)でアクセスでき、手動セットアップなしで全パネルにデータが描画される(`/api/report` を数回叩いて確認)
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 4. docs・steering 更新(運用手順 + Cloudflare 公開手順のユーザー作業)
  - File: docs/public-access.md(Public Hostname `grafana.<ドメイン>` → `http://localhost:3001` の追加 + Access アプリ設定の手順追記)、docs/home-server.md または README.md(Prometheus/Grafana の起動・ポート・docker/.env 設定の要点追記)、.spec-workflow/steering/tech.md(Key Dependencies に Prometheus / Grafana を追記)、.spec-workflow/steering/structure.md(`docker/` の説明「Kafka + kafka-ui の Docker Compose」を Prometheus / Grafana 追加後の実態に合わせる)
  - docs には Grafana admin パスワードの焼き付き(`GF_SECURITY_ADMIN_PASSWORD` は grafana-data volume の初回初期化時のみ有効)を明記する: **初回 `up -d` の前に docker/.env を用意**すること、初回以降の変更は `grafana-cli admin reset-admin-password` で行うこと(design の Grafana 節参照)
  - Cloudflare ダッシュボード操作(トンネルへの Public Hostname 追加・Access アプリ作成)はリポジトリ成果物のないユーザー作業であることを明記する。study-notes 側の決定ログ追記はリポジトリ外のため別リポジトリで対応する
  - 完了条件: docs の手順どおりに(ユーザーが)公開作業を行えば `https://grafana.<ドメイン>` が Access 認証つきで開けること。本番デプロイ後に Prometheus target UP(Linux での host.docker.internal 方式)も確認。このときホスト側 firewall(ufw 等)がコンテナ → ホストの `:8080` を遮断していると target が DOWN になるため、DOWN の場合はまず firewall を疑う(トラブルシュートとして docs にも一言添える)
  - _Requirements: 6.1, 6.2_
