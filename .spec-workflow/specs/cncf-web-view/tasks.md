# Tasks Document

各タスクは TDD(Red → Green → Refactor)で進める。テストが先・実装が後。Task 1 のみ挙動不変の移動リファクタ(既存テストの green 維持が保証)。

- [ ] 1. CNCF 語彙(CncfMaturity・CncfMention)を shared/contract へ移動(挙動不変・単独コミット)
  - File: shared/contract/Cncf.kt(新規)、notify 側の定義削除 + import 更新
  - Test: 変更なし(全既存テスト green の維持のみ)
  - _Requirements: 3.1, 3.4_

- [ ] 2. 辞書・マッチャを keywords へ移し、CncfMatchPort 経由に置き換え(テスト込み)
  - File: keywords/domain/CncfProjects.kt・CncfProjectMatcher.kt(notify/domain から移動)、capabilities/CncfMatchPort.kt、keywords/application/CncfMatchPortImpl.kt、notify/application/BuildCncfDigestUseCase.kt(matcher 直接生成 → Port 注入)
  - Test: CncfProjectsTest・CncfProjectMatcherTest を keywords/domain へ移動(内容不変)、keywords/application/CncfMatchPortImplTest.kt(委譲・整列)、BuildCncfDigestUseCaseTest はフェイク Port 注入に更新(検証内容は不変)
  - 注: 辞書移動と Port 導入は BuildCncfDigestUseCase のコンパイルを保つため同一コミットで行う
  - _Requirements: 3.2, 3.3, 3.4, 1.4_

- [ ] 3. BuildCncfReportUseCase(report/application)を実装(テスト込み)
  - File: report/application/BuildCncfReportUseCase.kt(CncfReport・CncfReportArticle 含む)
  - Test: report/application/BuildCncfReportUseCaseTest.kt(tier 順・tier 内新着順・guid 決定性・言及付与・言及なし最後尾・0 件)
  - _Requirements: 1.2, 1.3_

- [ ] 4. CncfReportController(report/presentation)を実装(テスト込み)
  - File: report/presentation/CncfReportController.kt
  - Test: report/presentation/CncfReportControllerTest.kt(default days=7・境界 1/365・400・JSON 形)
  - _Requirements: 1.1_

- [ ] 5. index.html に CNCF セクションを追加 + README 追記
  - File: src/main/resources/static/index.html、README.md(Web UI 節があれば追記)
  - Test: 手動確認(表示・日数トグル連動・API 失敗時の独立性)
  - _Requirements: 2.1, 2.2, 2.3, 2.4_
