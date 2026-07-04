# Project Structure

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
    │   │   ├── config/      # Kafka・スケジューラ・フィード定義の設定クラス
    │   │   ├── model/       # ドメインモデル(RssItem 等)と Kafka メッセージ型
    │   │   ├── fetch/       # fetcher: RSS 巡回(@Scheduled)・Rome パース・publish
    │   │   ├── keywords/    # 技術キーワード辞書と抽出ロジック
    │   │   ├── consumer/    # sink / live の 2 consumer group
    │   │   ├── db/          # リポジトリ層(SQLite。将来 PostgreSQL に差し替え可能に)
    │   │   └── web/         # 集計 API・SSE エンドポイント
    │   └── resources/
    │       ├── application.yml
    │       └── static/      # ブラウザ UI(素の HTML/JS)
    └── test/kotlin/dev/rockyh/rsswatch/
```

- ルートは Gradle 単一モジュール。fetcher / consumer / web を 1 アプリに同居させる(プロファイルや設定での分離は将来検討)
- ドキュメント(設計・決定ログ・開発ログ)は study-notes リポジトリの `docs/rss-watch/` に置く。このリポジトリの `.spec-workflow/` には spec 駆動開発用の steering / specs を置く

## Naming Conventions

### Files

- **クラスファイル**: `PascalCase.kt`(例: `FeedFetcher.kt`, `SinkConsumer.kt`)
- **テスト**: `[ClassName]Test.kt`

### Code

- **Classes/Types**: `PascalCase`
- **Functions/Variables**: `camelCase`
- **Constants**: `UPPER_SNAKE_CASE`(companion object 内)
- **パッケージ**: `dev.rockyh.rsswatch.*` 小文字

## Import Patterns

- ワイルドカード import は使わない
- レイヤー間は上位(web)→ 下位(db)方向にのみ依存する

## Module Boundaries

- **fetch → keywords**: fetcher が publish 前にキーワード抽出を呼ぶ(topic には抽出済みメッセージを流す)
- **consumer → db**: DB に触るのは sink consumer とレポート API だけ。live consumer は DB を経由しない
- **db(リポジトリ層)**: SQLite 依存をこの層に閉じ込め、PostgreSQL への差し替えポイントにする
- **model**: どの層からも参照可。他層への依存を持たない

## Code Size Guidelines

- 1 ファイル 1 クラス(+ 小さな関連型)を基本にする
- 300 行を超えるファイルは分割を検討する

## Documentation Standards

- 設計・アーキテクチャ決定・開発ログは study-notes(`docs/rss-watch/`)に書く。**Python 試作の経緯は書かない**
- README には起動方法と構成の要点のみ。詳細設計は steering / specs を参照する
