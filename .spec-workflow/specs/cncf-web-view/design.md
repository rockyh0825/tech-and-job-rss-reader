# Design Document

## Overview

PR #48 の CNCF ダイジェストは Discord 専用で、Web/API 面を持たない。本 spec では (1) CNCF 照合ロジックを feature 境界に沿って再配置し、(2) report feature に `GET /api/cncf` を追加、(3) `index.html` に CNCF タブ(通常 / CNCF 切り替え)を追加する。

## 配置の再設計(Requirement 3)

PR #48 では辞書・マッチャ・語彙を notify/domain に置いた(当時の利用者が notify のみだったため)。report からも使うには Konsist ルール上そのままでは参照できないので、次のように再配置する:

| 型 | 旧 | 新 | 理由 |
|---|---|---|---|
| `CncfMaturity` / `CncfMention` | notify/domain | `shared/contract/Cncf.kt` | feature 横断の語彙。notify/domain(CncfCandidate 等)と report/application の両方が参照する。domain が import できるのは shared.contract のみ |
| `CncfProjects` / `CncfProject` / `CncfProjectMatcher` | notify/domain | `keywords/domain/` | 「テキスト → 技術語彙」の辞書照合は keywords feature の責務(KeywordExtractor と同居)。境界照合イディオムの複製コメントも解消方向 |
| (新規)`CncfMatchPort` | — | `capabilities/` | 依存する側: notify/application(ダイジェスト)・report/application(Web レポート)。実装する側: keywords/application(`CncfMatchPortImpl`、無条件 Bean) |

- `BuildCncfDigestUseCase` は `CncfProjectMatcher` の直接生成をやめ、`CncfMatchPort` をコンストラクタ注入に置き換える(挙動不変)
- `CncfMatchPortImpl` は webhook 設定にガードされない無条件 `@Component`。これにより「notify の Bean は webhook 未設定時ゼロ」という既存の Feature Toggle 不変条件を保ったまま、Web UI は常時有効にできる

## API(Requirement 1)

- `report/presentation/CncfReportController` — `GET /api/cncf?days=N`。バリデーションは `ReportController` と同一(default 7、1..365 外は 400)
- `report/application/BuildCncfReportUseCase` — `ArchiveQueryPort.itemsByCategory(ItemCategory.CNCF, days)` で取得 → `CncfMatchPort` で照合 → 並べ替え
- 並び順は `CncfDigestSelectionPolicy` と同じ全順序(tier 昇順 null 最後尾 → publishedAt 降順 null 最古扱い → guid 昇順)。cap はしないため notify のポリシー(cap 込み)はそのまま notify に残し、report 側は比較器のみを持つ

### レスポンス形

```json
{
  "articles": [
    {
      "item": { "guid": "...", "feedName": "Kubernetes Blog", "title": "...", "url": "...", "publishedAt": "...", ... },
      "mentions": [ { "projectName": "Kepler", "maturity": "SANDBOX" } ],
      "tier": "SANDBOX"
    }
  ]
}
```

既存 `/api/report` と同様、`RssItem` をそのままシリアライズする(厳密な DTO 分離はしない方針)。

## Web UI(Requirement 2)

- ヘッダーにタブ(通常 / CNCF)を追加し、`<main>` を 2 つ(既存レイアウトの `#view-main` / 1 カラムの `#view-cncf`)用意して表示を切り替える(セクション追加だと 1 ページが読みづらくなる、というユーザー要望による)
- CNCF タブは記事ごとに tier バッジ + 言及プロジェクト名(`🌱 Sandbox: Kepler ・ 🧪 Incubating: Knative` 形式、言及なしは `☸️ CNCF`)+ タイトルリンク + feedName/日付
- 取得は表示中のタブの分だけ(`loadActiveView()`)。日数トグルとタブ切り替えの両方で取り直すため表示と `state.days` は食い違わない。失敗時は CNCF タブ内にのみエラー表示(通常タブに影響しない)

## テスト方針

- 移動(Requirement 3)は挙動不変のリファクタ: 既存テストを移動先パッケージへ移すのみで内容は変えず green を維持
- `CncfMatchPortImpl` は委譲と整列(成熟度の低い順)の単体テスト
- `BuildCncfReportUseCase` はフェイク Port(ArchiveQueryPort / CncfMatchPort)で並び順・言及付与・空を検証
- `CncfReportController` は既存 `ReportControllerTest` と同じ standalone MockMvc + フェイクで default/バリデーション/JSON 形を検証
- `index.html` は手動確認(既存 UI にテスト基盤なし)
