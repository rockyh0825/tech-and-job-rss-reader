# Requirements Document

> Source: [Issue #11](https://github.com/rockyh0825/tech-and-job-rss-reader/issues/11)

## Introduction

現在の Web UI(クロスリンク表示 + SSE 新着)は認証なしで、自宅サーバーの外に公開できない。**Cloudflare Tunnel + Cloudflare Access**(Zero Trust Free プラン。最大 50 ユーザーまで無料)をアプリの前段に置くことで、**自分と許可した数人だけ**がアクセスできる状態で外部公開する。

方針として、**アプリ本体にはコードを追加しない**(Spring Security 等の認証をアプリに実装しない)。認証・TLS 終端・アクセス制御はすべて Cloudflare のエッジで行い、自宅サーバーは `cloudflared` が Cloudflare へ**アウトバウンド接続**するだけにする。これにより、ポート開放・自宅グローバル IP の公開・DDNS のいずれも不要になる。

## Alignment with Product Vision

- product.md の主要ユーザーは「開発者本人」だが、issue #11 のとおり信頼できる数人に収集記事を見せられるようにする
- アクセス制御は Cloudflare Access のポリシー(メール許可リスト)で管理し、アプリ側にユーザー管理・ログイン画面を作り込まない。個人ツールの範囲を超えない
- tech.md の運用方針(fat jar + systemd + docker compose)を変えず、`cloudflared` を 1 プロセス足すだけに留める

## Requirements

### Requirement 1: 認証ゲート(エッジ)

**User Story:** As a 運用者, I want 許可した ID だけが UI と API に到達できること, so that 外部公開しても収集データが第三者に見られない

#### Acceptance Criteria

1. WHEN 未認証で公開ホスト名(UI・`/api/report`・`/api/stream`)にアクセスした THEN Cloudflare Access SHALL ログイン(メール OTP または IdP)を要求し、**オリジン(自宅サーバー)にリクエストを一切到達させない**
2. WHEN 許可された ID で認証を通過した THEN Cloudflare SHALL 既存のすべての機能(UI・レポート API・SSE)をそのまま提供する
3. オリジンへの唯一の経路は Cloudflare Tunnel とし、アプリの `:8080` はインターネットに直接公開しない(ポート開放しない)

### Requirement 2: 許可ユーザー管理

**User Story:** As a 運用者, I want 許可する相手を設定だけで管理できること, so that DB やアプリ改修なしに数人へ共有できる

#### Acceptance Criteria

1. アクセスを許可する相手は Cloudflare Access の Policy(許可メールアドレス、またはメールドメイン)で定義すること
2. WHEN Policy に相手を追加・削除した THEN **アプリの再起動もコード変更も不要**で、即時に反映されること
3. Cloudflare の認証方式はメール OTP(ワンタイム PIN)を既定とし、必要なら Google / GitHub 等の IdP に切り替えられること

### Requirement 3: 閲覧体験

**User Story:** As a 閲覧者, I want ブラウザで一度認証すれば使い続けられること, so that スマホからも気軽に見られる

#### Acceptance Criteria

1. WHEN ブラウザで初回アクセスした THEN Cloudflare Access SHALL 認証フローを提供し、通過後はセッション Cookie(`CF_Authorization`)で以降のアクセスを許可する
2. WHEN 認証済みセッションで SSE(`/api/stream`)へ接続した THEN 配信 SHALL 追加の認証操作なしで機能する(`EventSource` は Cookie を自動送出するため)
3. セッションの有効期間は Cloudflare Access の Session Duration で管理し、失効後は要件 1.1 に従って再認証を求める

### Requirement 4: 外部露出の最小化

**User Story:** As a 運用者, I want 自宅ネットワークを晒さずに公開したい, so that 攻撃面を最小化できる

#### Acceptance Criteria

1. 公開のために自宅ルーターのポート開放・ポートフォワードを行わないこと(`cloudflared` のアウトバウンド接続のみ)
2. 自宅のグローバル IP を公開しないこと(DNS はトンネルの CNAME を指す)
3. HTTPS(TLS)終端は Cloudflare エッジで行い、証明書の取得・更新をアプリ/サーバー側で運用しないこと

### Requirement 5: 既存機能の無影響

**User Story:** As a 開発者, I want 公開の追加で既存パイプラインもコードも変わらないこと, so that 安心して導入・撤去できる

#### Acceptance Criteria

1. Kafka consumer(sink / live / notify)・fetcher・レポート API・SSE の**アプリコードを一切変更しない**こと
2. 既存のテストスイートに変更・追加が不要であること(この spec はアプリの振る舞いを変えないため)
3. `cloudflared` を停止すれば、公開だけが止まりアプリ本体は自宅内で従来どおり動作すること

## Non-Functional Requirements

### 前提条件(Prerequisite)

- Cloudflare Access / Tunnel のソフトウェアと Zero Trust Free プランは無料だが、**Cloudflare が管理するドメイン(DNS を Cloudflare に向けたゾーン)が 1 つ必要**。DNS 管理自体は無料だが、ドメイン登録費用(年数百円〜)は別途必要か、既存の所有ドメインを利用する
- 50 ユーザーを超えると有料($7/user/月)。本ツールの想定(数人)では無料枠に収まる

### Security

- Tunnel の資格情報(トンネルトークン / credentials ファイル)はシークレットとして扱い、環境変数またはサーバー上の保護されたファイルで管理し、**リポジトリにコミットしない**
- Cloudflare Access を経由しない経路(自宅 LAN からの `:8080` 直アクセス)は認証されない。これは現状と同じ信頼レベル(自宅 LAN 内)であり個人ツールとして許容する。より厳格にしたい場合の防御多層化(オリジンでの `Cf-Access-Jwt-Assertion` 検証)は design の「任意の追加ハードニング」に記載する

### Usability

- 認証 UI は Cloudflare Access の既定画面を使い、見た目の作り込みはしない

### Documentation

- 公開手順(ドメイン準備 → Tunnel 作成 → Access ポリシー設定 → cloudflared 常駐)と、公開前チェックリスト(トークンをコミットしない・ポート開放しない・許可メールを最小限にする)を README に記載する
