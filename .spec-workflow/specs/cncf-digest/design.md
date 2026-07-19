# Design Document

> Source: [Issue #46](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/46) / requirements.md 参照

## 全体像

```
feeds.toml (category = "cncf")
  → fetch → Kafka(rss.items) → sink → items(category = 'cncf')   ← 既存パイプラインそのまま
                                          │
        CncfDigestScheduler(cron 8:10)────┘ 読み取りのみ
          → BuildCncfDigestUseCase
              ├ ArchiveQueryPort.itemsByCategory(CNCF, windowDays)   ← 既存 Port 再利用
              ├ PostedGuidStore(notify_posted 共有)                  ← 既存再利用
              ├ CncfProjectMatcher × CncfProjects 辞書(成熟度付き)  ← 新規 domain
              ├ CncfDigestSelectionPolicy(tier 順 + cap)            ← 新規 domain
              ├ Summarizer / ThumbnailResolver                       ← 既存再利用
              └ CncfDigestPublisher → CncfDiscordWebhookClient
                                        └ DiscordPoster(transport 共有) ← 既存から抽出
```

## 主要な設計判断

### 1. `ItemCategory.CNCF` の追加

`shared/contract/ItemCategory.kt` に `CNCF("cncf")` を追加するだけ。src/main に exhaustive `when` は存在せず、`RssItem.category` は String・`items.category` は TEXT のため、Kafka 契約・sink・live SSE は無変更で通る。既存ダイジェストは `ItemCategory.TECH` を明示指定しているため、CNCF 記事が混入しないことはカテゴリ分離で自動的に成立する(要件 4.3)。

### 2. CNCF プロジェクト辞書は notify/domain・マッチはダイジェスト構築時

- 成熟度バッジは**通知専用の表示関心事**のため、fetch 時のキーワード抽出(`keywords` feature、`item_keywords` 永続化)には載せない。fetch 時抽出だと保存済み記事に遡って効かず、スキーマ変更も必要になる。notify 時マッチならどちらも不要
- `notify/domain/CncfProjects.kt` に手書き Kotlin 辞書(`Keywords.kt` の前例踏襲)。`CncfMaturity` enum(GRADUATED 🎓 / INCUBATING 🧪 / SANDBOX 🌱)+ `CncfProject(name, maturity, aliases)`。初期規模: graduated 全件 + incubating 全件 + sandbox 厳選
- `CncfProjectMatcher` は `KeywordExtractor` の境界正規表現イディオム(`(?<![A-Za-z0-9])…(?![A-Za-z0-9+#])`、ignore-case / exact-case エイリアス)を**意図的に小複製**する。Konsist の feature 分離ルールで `keywords.domain` を import できないため(コメントで出典を明記)。Harbor / Helm / Envoy / Argo など一般名詞と衝突する名前は exact-case のみでマッチ(要件 3.4)

### 3. Discord transport の抽出(`DiscordPoster`)

既存 `DiscordWebhookClient` は transport(リトライ・429 Retry-After・clamp・Embed DTO・SendResult)と tech ダイジェスト固有の表示(求人言及数の author 行・CTA 文言)が同居している。transport を **plain class `DiscordPoster`**(Spring Bean にしない。コンストラクタで `RestClient.Builder` / webhookUrl / maxRetries / sleeper を受け取る)へ抽出し、`DiscordWebhookClient`(tech)と `CncfDiscordWebhookClient`(CNCF)が各自の `@Value` URL で組み立てる。Bean qualifier 不要で 2 チャンネル構成が成立する。抽出は挙動不変の純リファクタとして単独コミットで行う(既存 `DiscordWebhookClientTest` が安全網)。

### 4. 条件付き Bean の 3 段構え

| アノテーション | 条件プロパティ | 対象 |
|---|---|---|
| `@ConditionalOnNotifyEnabled`(既存) | `rss-watch.notify.discord-webhook-url` | DigestScheduler / BuildDigestUseCase / DiscordWebhookClient / FeaturedTechRepository / NotifyInterestsConfig |
| `@ConditionalOnCncfNotifyEnabled`(新規) | `rss-watch.notify.cncf.discord-webhook-url` | CncfDigestScheduler / BuildCncfDigestUseCase / CncfDiscordWebhookClient |
| `@ConditionalOnAnyNotifyEnabled`(新規、`AnyNestedCondition`) | どちらか一方でも設定 | ClaudeSummarizer / OgpThumbnailResolver / PostedGuidRepository(共有部品) |

共有部品は現状 tech 側プロパティにガードされており、**CNCF のみ有効なデプロイでは wiring が起動時に失敗する**。`AnyNestedCondition`(REGISTER_BEAN フェーズ)への差し替えが本設計の要(要件 4.2)。`NotifyFeatureToggleTest` に「CNCF のみ / 両方 / どちらもなし」の 3 コンテキストを追加して回帰ガードとする。

### 5. `notify_posted` は共有・マイグレーションなし

`items.guid` は PK でカテゴリは排他のため、両ダイジェストの guid 集合は交差しない。除外セットに他チャンネル分が混ざっても各自のカテゴリフィルタで不活性。channel カラム追加はマイグレーション + 複合キー化のコストに対し挙動差ゼロのため採用しない。

### 6. 選定・順序・embed

- 候補: `itemsByCategory(CNCF, windowDays=7)` − `postedGuids(EPOCH)`
- tier: 記事の言及プロジェクトのうち**最も成熟度が低いもの**で決定(「早期に掴む」動機の反映)。sandbox → incubating → graduated → 言及なしの順、tier 内は publishedAt 新しい順、上限 `max-articles`(既定 8)
- author 行: `🌱 Sandbox: Kueue ・ Kubernetes, Prometheus`(最低成熟度のバッジ + 残り最大 2 件併記)/ 言及なしは `☸️ CNCF`。末尾に CNCF 用 CTA embed(既存パターン踏襲)
- 初回有効化時は最大 windowDays 分のバックログがあるが cap で抑制、残りは window から自然に外れる(既存ダイジェストと同じ回復セマンティクス)

### 7. スケジューリング

CNCF cron 既定 `0 10 8 * * *`(tech の 8:00 と 10 分オフセット)。`spring.task.scheduling.pool.size` を 2 → 3(fetch + 2 ダイジェストの同時実行でも相互ブロックしない)。既存 yml テストは `>= 2` 断言のため green のまま。

## 新規・変更ファイル一覧

**新規(main)**
- `notify/ConditionalOnCncfNotifyEnabled.kt` / `notify/ConditionalOnAnyNotifyEnabled.kt`
- `notify/domain/CncfProjects.kt`(CncfMaturity / CncfProject / 辞書)
- `notify/domain/CncfProjectMatcher.kt`(CncfMention を返す)
- `notify/domain/CncfDigest.kt`(CncfDigestEntry。DigestArticle / PostOutcome 再利用)
- `notify/domain/CncfDigestSelectionPolicy.kt`
- `notify/domain/CncfDigestPublisher.kt`(port)
- `notify/infrastructure/DiscordPoster.kt`(既存から抽出)
- `notify/infrastructure/CncfDiscordWebhookClient.kt`
- `notify/application/BuildCncfDigestUseCase.kt`
- `notify/presentation/CncfDigestScheduler.kt`

**変更**
- `shared/contract/ItemCategory.kt`(CNCF 追加)
- `notify/infrastructure/DiscordWebhookClient.kt`(transport を DiscordPoster へ委譲)
- `notify/infrastructure/ClaudeSummarizer.kt` / `OgpThumbnailResolver.kt` / `PostedGuidRepository.kt`(`@ConditionalOnAnyNotifyEnabled` へ差し替え)
- `application.yml`(pool.size 3、`rss-watch.notify.cncf.{cron, window-days, max-articles}`。webhook-url は宣言しない = 未設定でオフ)
- `feeds.toml`(CNCF Blog / Kubernetes Blog、ヘッダコメント更新)
- `README.md`(CNCF 配信の設定手順)

**Flyway マイグレーション: なし**

## Testing Strategy

- 純 domain(辞書・マッチャ・選定)は表駆動の単体テスト
- `CncfDiscordWebhookClientTest` は MockRestServiceServer + 注入 sleeper(既存 `DiscordWebhookClientTest` 踏襲)
- `BuildCncfDigestUseCaseTest` はポートのフェイクで編成をテスト(既存 `BuildDigestUseCaseTest` 踏襲)
- `NotifyFeatureToggleTest` に 3 コンテキスト追加(条件付き 3 段構えの回帰ガード)
- `DiscordPoster` 抽出は既存テスト green 維持で担保(新テスト先行なしの純リファクタ)
- Konsist `ArchitectureTest` で層・feature 分離を検証
