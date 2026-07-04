# tech-and-job-rss-reader

技術記事と求人情報を RSS で収集し、「求人で言及されている技術」と「その技術の記事」をクロスリンクして眺めるツール。Kotlin + Spring Boot + Kafka で実装し、自宅サーバーで運用する。

## まず読むこと(spec-workflow steering)

- [プロダクト概要・機能・MVP スコープ](.spec-workflow/steering/product.md)
- [技術スタック・アーキテクチャ・決定ログ要約](.spec-workflow/steering/tech.md)
- [ディレクトリ構成・レイヤー責務・命名規則](.spec-workflow/steering/structure.md)

実装は `.spec-workflow/specs/` の spec(requirements → design → tasks)に沿って進める。現在の spec: `mvp-rss-pipeline`。

## ドキュメントの置き場所

- 設計ノート・アーキテクチャ決定ログ・開発ログは **study-notes リポジトリの `docs/rss-watch/`** に書く
- Python 試作の経緯はドキュメントに書かない(このプロジェクトは最初から Kotlin として扱う)
