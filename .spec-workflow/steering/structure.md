# Project Structure

## 設計方針

**Package by Feature + Layer within Feature + Capability(Port)for cross-feature**(cleaning-app と同一方針)

- コードは**機能(feature)単位**でパッケージを切る
- 各 feature の内部は**レイヤー(presentation / application / domain / infrastructure)**で整理する
- **domain 中心**: domain は Spring・Kafka・Rome・PostgreSQL に依存しない純 Kotlin。依存は必ず外側(presentation / infrastructure)から内側(domain)へ向ける
- feature をまたぐ依存は **`capabilities/` の Port(インターフェース)経由**のみ。他 feature を直接 import しない
- **空のレイヤーは作らない**。このアプリはパイプラインで、feature によっては domain がほぼ無い(live 等)。不要なレイヤーのディレクトリは置かないが、「その種のコードを書くならこのレイヤー」という置き場所のルールは固定

## Directory Organization

```
tech-and-job-rss-reader/
├── .spec-workflow/          # spec-workflow の steering / specs
├── docker/                  # Kafka + kafka-ui の Docker Compose
│   └── docker-compose.yml
├── feeds.toml               # 収集フィード定義(category = "tech" | "jobs")
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/dev/rockyh/rsswatch/
    │   │   ├── RssWatchApplication.kt
    │   │   ├── shared/                  # 機能横断の共通コード
    │   │   │   ├── contract/            # RssItem(Kafka メッセージ契約。全 feature・将来のサービス間の契約)
    │   │   │   └── config/              # Kafka・スケジューラ等の Spring 設定
    │   │   ├── capabilities/            # feature 間境界インターフェース(Port)
    │   │   │   ├── KeywordExtractionPort.kt   # fetch → keywords
    │   │   │   └── ArchiveQueryPort.kt        # report → archive
    │   │   ├── fetch/                   # feature: RSS 収集
    │   │   │   ├── presentation/        # FetchScheduler(@Scheduled 起動)
    │   │   │   ├── application/         # FetchFeedsUseCase
    │   │   │   ├── domain/              # FeedDefinition(フィード定義とカテゴリのルール)
    │   │   │   └── infrastructure/      # FeedConfigLoader(feeds.toml)・RomeFeedParser・KafkaItemPublisher
    │   │   ├── keywords/                # feature: 技術キーワード抽出
    │   │   │   ├── application/         # KeywordExtractionPortImpl(Port 実装)
    │   │   │   └── domain/              # Keywords 辞書・KeywordExtractor(純 Kotlin。最重要ロジック)
    │   │   ├── archive/                 # feature: 蓄積
    │   │   │   ├── presentation/        # SinkConsumer(Kafka リスナー、groupId = "sink")
    │   │   │   ├── application/         # StoreItemsUseCase・ArchiveQueryPortImpl(Port 実装)
    │   │   │   └── infrastructure/      # RssItemRepository(kuery-client で PostgreSQL に生 SQL。スキーマは Flyway)
    │   │   ├── live/                    # feature: リアルタイム新着(domain なし: Kafka → SSE 直結)
    │   │   │   ├── presentation/        # LiveConsumer(groupId = "live")・SseController
    │   │   │   └── application/         # SseBroadcaster(接続管理・配信)
    │   │   └── report/                  # feature: クロスリンクレポート
    │   │       ├── presentation/        # ReportController(GET /api/report)
    │   │       ├── application/         # BuildReportUseCase
    │   │       └── domain/              # クロスリンク組み立てルール(求人技術 × 関連記事)
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/            # Flyway マイグレーション(スキーマの唯一の正本)
    │       └── static/                  # ブラウザ UI(素の HTML/JS)
    └── test/kotlin/dev/rockyh/rsswatch/ # main と同じ feature/レイヤー構成 + architecture/(Konsist)
```

- MVP は Gradle 単一モジュール。fetch / archive / live / report を 1 アプリに同居させる
- ドキュメント(設計・決定ログ・開発ログ)は study-notes リポジトリの `docs/rss-watch/` に置く。このリポジトリの `.spec-workflow/` には spec 駆動開発用の steering / specs を置く

## レイヤーの責務(feature 内)

| レイヤー | 責務 | 依存先 |
|---|---|---|
| `presentation/` | 外部からの入力アダプタ: HTTP・Kafka リスナー・@Scheduled。入出力の変換のみ | `application/` |
| `application/` | ユースケース実装。Port 実装もここに置く | `domain/`, `capabilities/` |
| `domain/` | ビジネスルール。Spring・Kafka・Rome・PostgreSQL に非依存(純 Kotlin) | なし(`shared/contract/` のみ可) |
| `infrastructure/` | 外部への出力アダプタ: PostgreSQL・Rome・Kafka producer・ファイル読み込み | `domain/`, `shared/` |

※ cleaning-app では presentation = HTTP だが、このアプリの入口は Kafka リスナーと @Scheduled も含むため「外部からの入力アダプタ」に拡張して定義する。

## 依存方向のルール

```
presentation/ → application/ → domain/
                     ↓
               capabilities/  ← 他 feature の application/ が実装
infrastructure/ → domain/
```

- feature 内のレイヤーは外側から内側への一方向依存のみ
- `<feature A>/` から `<feature B>/` への直接 import は**禁止**。必ず `capabilities/` の Port 経由(fetch → keywords は `KeywordExtractionPort`、report → archive は `ArchiveQueryPort`)
- Port は**使う側のニーズに合わせて**定義し、**提供する側の application/** に実装を置く。結合は Spring DI(コンストラクタインジェクション)が行う(cleaning-app の di.ts に相当する配線ファイルは不要)
- `shared/contract/` の RssItem は Kafka メッセージ契約であり、全 feature から参照可。厳密な DTO/ドメインモデル分離はしない(パイプラインアプリでは過剰)。振る舞いが必要な feature だけ自前のドメイン型に写す
- これらのルールは `src/test/kotlin/.../architecture/ArchitectureTest.kt`(Konsist)で強制する

## マイクロサービスへの発展方針

Discord への定期 push など、新しい後段処理はすべて **topic `rss.items` を新しい consumer group で購読する feature** として追加する。既存 feature の変更は不要。

- **契約はメッセージスキーマ**: `shared/contract/` の RssItem(JSON)が feature 間・将来のサービス間の契約。フィールドは後方互換な追加のみ許可し、削除・改名はしない(consumer が複数いるため)
- **feature = 将来のサービス境界**: `fetch`(収集)、`archive` + `report`(蓄積・閲覧)、`live`(リアルタイム配信)、将来の `discord-notifier`(定期 push)はそれぞれ独立サービスに切り出せる単位として設計する。feature 内が layered なので、切り出し時はパッケージごと移動するだけでよい
- **サービス分割時のリポジトリ構成**(2 サービス目が必要になった時点で multi-module 化する):

```
├── settings.gradle.kts
├── apps/
│   ├── rss-watch/           # 既存アプリ(feature パッケージをそのまま移動)
│   └── discord-notifier/    # 例: rss.items を購読して Discord へ定期 push
└── libs/
    └── contracts/           # メッセージ型(shared/contract/ を昇格)
```

## Naming Conventions

### Files

| 種別 | 形式 | 例 |
|---|---|---|
| クラスファイル | `PascalCase.kt` | `FeedFetcher.kt` |
| ユースケース | PascalCase + `UseCase.kt` | `FetchFeedsUseCase.kt` |
| Port(interface) | PascalCase + `Port.kt` | `KeywordExtractionPort.kt` |
| Port 実装 | PascalCase + `PortImpl.kt` | `KeywordExtractionPortImpl.kt` |
| テスト | `[ClassName]Test.kt`(main と同じパッケージに置く) | `KeywordExtractorTest.kt` |
| Flyway マイグレーション | `V{番号}__{機能}_{説明}.sql` | `V1__archive_initial.sql` |

### Code

- **Classes/Types**: `PascalCase`
- **Functions/Variables**: `camelCase`
- **Constants**: `UPPER_SNAKE_CASE`(companion object 内)
- **パッケージ**: `dev.rockyh.rsswatch.<feature>.<layer>` 全小文字

## Import Patterns

- ワイルドカード import は使わない
- feature 間の参照は `capabilities/` の Port 経由のみ(上記「依存方向のルール」参照)

## Code Size Guidelines

| 種別 | 目安 |
|---|---|
| ファイルサイズ | 300 行以内(1 ファイル 1 クラス + 小さな関連型) |
| ユースケースクラス | 100 行以内(1 ユースケース 1 クラス) |
| 関数・メソッド | 40 行以内 |

## Documentation Standards

- 設計・アーキテクチャ決定・開発ログは study-notes(`docs/rss-watch/`)に書く。**Python 試作の経緯は書かない**
- `capabilities/` の各 Port に、誰が依存し誰が実装するかをコメントで明記する
- README には起動方法と構成の要点のみ。詳細設計は steering / specs を参照する
