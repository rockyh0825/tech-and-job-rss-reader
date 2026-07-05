# Requirements Document

> Source: [Issue #11](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/11)

## Introduction

現在の Web UI(クロスリンク表示 + SSE 新着)は認証なしで、自宅サーバーの外に公開できない。Spring Security による認証を追加し、**自分と許可した数人だけ**がアクセスできる状態にした上で外部公開できるようにする。

## Alignment with Product Vision

- product.md の主要ユーザーは「開発者本人」だが、issue #11 のとおり信頼できる数人に収集記事を見せられるようにする
- 認証は最小構成(設定ファイルベースのユーザー定義)とし、ユーザー管理画面などは作らない。個人ツールの範囲を超えない

## Requirements

### Requirement 1: 認証必須化

**User Story:** As a 運用者, I want 認証を通った人だけが UI と API にアクセスできること, so that 外部公開しても収集データが第三者に見られない

#### Acceptance Criteria

1. WHEN 未認証で `/`(静的 UI)・`/api/report`・`/api/stream` にアクセスした THEN サーバー SHALL 401 またはログインページへの誘導を返し、コンテンツを一切返さない
2. WHEN 正しい認証情報でアクセスした THEN サーバー SHALL 既存のすべての機能(UI・レポート API・SSE)を提供する
3. ヘルスチェック等の運用エンドポイントを設ける場合は、認証除外の対象を明示的にホワイトリストで管理すること

### Requirement 2: ユーザー定義

**User Story:** As a 運用者, I want ユーザーを設定で管理できること, so that DB やユーザー管理画面を作り込まずに数人へ共有できる

#### Acceptance Criteria

1. ユーザー(ユーザー名 + パスワード)は設定(環境変数または application.yml の外部化設定)で定義すること
2. パスワードは bcrypt 等のハッシュで設定に置けること(平文をリポジトリにコミットしない)
3. WHEN 設定にユーザーを追加した THEN システム SHALL 再起動のみで(コード変更なしで)新ユーザーを受け入れる

### Requirement 3: ログイン体験

**User Story:** As a 閲覧者, I want ブラウザで一度ログインすれば使い続けられること, so that スマホからも気軽に見られる

#### Acceptance Criteria

1. WHEN ブラウザでアクセスした THEN サーバー SHALL フォームログイン(セッション Cookie)を提供する
2. WHEN ログイン済みセッションで SSE(`/api/stream`)へ接続した THEN 配信 SHALL 追加の認証操作なしで機能する
3. WHEN ログアウトした THEN セッション SHALL 無効化され、以降のアクセスは要件 1.1 に従う

### Requirement 4: 既存機能の無影響

**User Story:** As a 開発者, I want 認証追加で既存パイプラインが壊れないこと, so that 安心して導入できる

#### Acceptance Criteria

1. Kafka consumer(sink / live)・fetcher の動作は認証の影響を受けないこと
2. 既存の統合テスト・MockMvc テストは認証を考慮した形で全件グリーンを維持すること

## Non-Functional Requirements

### Code Architecture and Modularity

- Security 設定は `shared/config/` に置き、各 feature のコードには手を入れない(フィルタチェーンで横断的に適用)

### Security

- 認証情報は環境変数で注入し、リポジトリにコミットしない
- 外部公開時の HTTPS 化はアプリ外(リバースプロキシ / Cloudflare Tunnel 等)で行い、手順を README に記載する
- セッション固定攻撃対策・CSRF は Spring Security のデフォルトを基本とし、無効化する場合(SSE・API)は理由を設計に明記する

### Usability

- ログインページは Spring Security デフォルトで開始してよい(見た目の作り込みは任意)
