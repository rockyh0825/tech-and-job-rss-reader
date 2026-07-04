# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

技術記事と求人情報を RSS で収集し、「求人で言及されている技術」と「その技術の記事」をクロスリンクして眺めるツール。Kotlin + Spring Boot + Kafka で実装し、自宅サーバーで運用する。

## Worktree ルール

他の作業との干渉を防ぐため、**作業開始時に必ず worktree を作成し、作業終了時に必ず削除**してください。

### 作業開始時

```bash
git worktree add /tmp/worktree-<feature-name> -b <branch-name>
```

- worktree のパスは `/tmp/worktree-<feature-name>` の形式を推奨
- main への直 push は避け、feature ブランチ + PR で進める(`tech.md` 参照)

### 作業終了時

```bash
git worktree remove /tmp/worktree-<feature-name>
```

- PR マージ後やタスク完了後は必ず remove する
- ロックされている場合は `git worktree remove --force -f` を使用する

---

## 実装の流れとTDDのルール

すべての新機能開発およびバグ修正において、テスト駆動開発(TDD)を厳格に適用してください。対応するテストが存在しない状態でプロダクションコードを先に書いてはいけません。

実装は `.spec-workflow/specs/` の spec(requirements → design → tasks)に沿って進める。現在の spec: `mvp-rss-pipeline`。

### テストの方針

- **正常系・境界値・異常系**を網羅し、動作保証として機能するテストを厚めに書く
- 単体テストを基本とし、Kafka をまたぐ振る舞いは EmbeddedKafka(spring-kafka-test)による結合テストで補う
- テスト名は「振る舞いを説明する文」にする(例: `returns_empty_list_when_no_items_exist`)
- テストの構造は **Arrange → Act → Assert** の順で書く
- 実装の詳細ではなく**入力と出力(振る舞い)**をテストする

### TDDサイクルの実行手順

1. **Red(テストの作成)**:
    - 適切なテストファイルに、まずテストケース(1つずつ)を記述する。
    - すぐに Bash ツールでテストコマンドを実行し、テストが**「失敗する(Red)」**ことを確認する。
    - (注意:失敗の確認をスキップしてはいけない。テストが正しく機能しているか検証するため)
2. **Green(最小限の実装)**:
    - 失敗したテストを通過させるために、*必要最小限*のプロダクションコードを書く。
    - **テスト自体を変更してはいけない**。必ずプロダクションコード側を修正すること。
    - 再度テストを実行し、テストが通過する(Green)ことを確認する。
3. **Refactor(リファクタリング)**:
    - 全テストがグリーンの状態でのみリファクタリングを行う。
    - コードの可読性や設計を整える。重複の除去・命名改善・メソッド抽出など。
    - 再度テストを実行し、デグレードが起きていないことを確認する。

## ステアリングドキュメント

設計・アーキテクチャ・規約・プロダクト方針はここを参照する(重複して記載しない):

- [プロダクト概要・機能・MVP スコープ](.spec-workflow/steering/product.md)
- [技術スタック・アーキテクチャ・決定ログ要約・ブランチ戦略](.spec-workflow/steering/tech.md)
- [ディレクトリ構成・レイヤー責務・命名規則](.spec-workflow/steering/structure.md)

