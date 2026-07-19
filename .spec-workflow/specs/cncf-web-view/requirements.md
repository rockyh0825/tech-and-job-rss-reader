# Requirements Document

## Introduction

CNCF 特化ダイジェスト(issue #46 / PR #48)で Discord に配信している「CNCF 記事 × プロジェクト言及 × 成熟度バッジ」を、Web UI(ブラウザ)でも閲覧できるようにする。Discord は毎朝の push(上限 8 件)だが、Web UI では日数を切り替えて窓内の全記事を成熟度優先で一覧できるようにする。

## Requirements

### Requirement 1: CNCF レポート API

**User Story:** ブラウザから直近の CNCF 記事を成熟度付きで取得したい。

#### Acceptance Criteria

1.1. `GET /api/cncf?days=N` は直近 N 日の `category = "cncf"` の記事一覧を返す(`days` 省略時 7、1〜365 以外は 400。既存 `/api/report` と同じ規約)
1.2. 各記事には CNCF プロジェクト言及(`projectName` + `maturity`)が付与される(言及なしは空リスト)
1.3. 並び順はダイジェストと同じ優先順: tier 昇順(sandbox → incubating → graduated → 言及なし)→ publishedAt 降順 → guid 昇順。**件数上限は設けない**(push と違い閲覧はスクロールできるため。窓が上限の役割を果たす)
1.4. Discord Webhook(既存 / CNCF)の設定有無に関わらず API は常に有効(通知機能とは独立)

### Requirement 2: Web UI の CNCF セクション

**User Story:** 既存の Web UI で、求人クロスリンクと同じ画面から CNCF 動向を眺めたい。

#### Acceptance Criteria

2.1. `static/index.html` に「CNCF 動向」セクションを追加し、`/api/cncf` の結果を表示する
2.2. 各記事に成熟度バッジ(🌱 Sandbox / 🧪 Incubating / 🎓 Graduated、言及なしは ☸️ CNCF)と言及プロジェクト名を表示する(記事の tier = 最も低い成熟度。Discord の author 行と同じ見せ方)
2.3. 既存の日数トグル(7/14/30 日)に連動する
2.4. 取得失敗・0 件でも既存セクションの表示を壊さない(独立したエラー/空表示)

### Requirement 3: CNCF 照合ロジックの共有(アーキテクチャ)

**User Story:** notify(ダイジェスト)と report(Web UI)で同じ辞書・同じ照合結果を使いたい。辞書の二重管理はしない。

#### Acceptance Criteria

3.1. 成熟度・言及の語彙(`CncfMaturity` / `CncfMention`)は `shared/contract/` に置く(feature 横断の語彙。ItemCategory と同格)
3.2. 辞書(`CncfProjects`)とマッチャ(`CncfProjectMatcher`)は keywords feature(技術語彙の抽出を担う feature)の domain に置く
3.3. feature 間の参照は `capabilities/CncfMatchPort`(実装は keywords/application)経由のみ。notify のダイジェストも同じ Port を使うように置き換え、notify/domain の辞書コピーは削除する
3.4. 既存の CNCF ダイジェスト(Discord 配信)の挙動は変えない(既存テストが green のまま)

## Non-Functional

- 照合は取得済み記事に対するオンメモリ処理(DB スキーマ変更・マイグレーションなし)
- `/api/cncf` の応答は既存 `/api/report` と同程度の軽さ(辞書照合は正規表現 90 件 × 記事数で十分軽い)
