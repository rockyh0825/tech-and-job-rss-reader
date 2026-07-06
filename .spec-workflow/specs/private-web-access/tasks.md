# Tasks Document

この spec はアプリのコード/振る舞いを変えない(認証は Cloudflare エッジで行う)。したがって大半は**インフラ・運用・ドキュメントのタスク**であり、TDD の対象外(tasks の TDD ルールはコード実装タスクに適用)。任意の追加ハードニング(タスク 7)を実装する場合のみ TDD を適用する。

- [ ] 1. Cloudflare の前提を整備
  - 対象: Cloudflare アカウント / Zero Trust(Free)組織、DNS を Cloudflare に向けたドメイン(既存所有 or 新規登録)
  - 公開ホスト名(例 `rss.example.com`)を決める。ここではまだトンネル/Access は作らない
  - Purpose: 無料枠(50 ユーザーまで)で公開する土台
  - _Requirements: Non-Functional(前提条件)_

- [ ] 2. named tunnel を作成し、公開ホスト名 → localhost:8080 を登録
  - 対象: Zero Trust → Networks → Tunnels で named tunnel を作成。Public Hostname に `rss.example.com` → Service `http://localhost:8080`
  - トンネルトークンを取得し、サーバー上で**シークレットとして保管**(リポジトリにコミットしない)
  - Purpose: オリジンへの唯一の経路(アウトバウンド)を確立
  - _Requirements: 1.3, 4.1, 4.2, 4.3_

- [x] 3. cloudflared を自宅サーバーに常駐させる
  - File: docker/docker-compose.yml(`cloudflare/cloudflared` サービス追加。トークンは環境変数参照)/ または systemd 常駐(`cloudflared service install`)
  - トークンは compose に直書きせず `.env` / 環境変数で注入し、`.gitignore` を確認
  - Purpose: トンネルを張り続ける(再起動後も自動復帰)
  - _Requirements: 4.1, 5.3_

- [ ] 4. Cloudflare Access アプリ + ポリシーを設定
  - 対象: Zero Trust → Access → Applications で Self-hosted アプリを作成し、ドメインに公開ホスト名を指定
  - Policy: 許可する数人のメールアドレス(または信頼ドメイン)を Allow。認証方式は One-time PIN を既定に
  - Session Duration を設定
  - Purpose: エッジでの認証ゲート(許可 ID のみ到達)
  - _Requirements: 1.1, 1.2, 2.1, 2.2, 2.3, 3.1, 3.3_

- [ ] 5. 全機能の疎通確認(手動 E2E)
  - 許可アカウント: 公開 URL → Access 認証 → クロスリンク UI → SSE 新着受信 まで通ること
  - 許可外アカウント: Access で拒否され、UI・API がオリジンに到達しないこと
  - ポリシー即時反映: 許可メール追加/削除がアプリ再起動なしで反映されること
  - フェイルセーフ: `cloudflared` 停止で公開のみ止まり、LAN 内・パイプラインが継続すること
  - Purpose: 要件どおりに公開・遮断・SSE が機能することの担保
  - _Requirements: 1.1, 1.2, 3.2, 5.1, 5.3_

- [x] 6. README に外部公開手順を追記
  - File: README.md
  - 手順(ドメイン準備 → Tunnel 作成 → cloudflared 常駐 → Access ポリシー)、公開前チェックリスト(トークンをコミットしない・ポート開放しない・許可メールを最小限に)、無料枠の条件(50 ユーザー・要ドメイン)を記載
  - Purpose: 自宅サーバーからの安全な外部公開手順
  - _Requirements: Non-Functional(Documentation)_

- [x] 7.(任意 / Phase 2)オリジンでの Cf-Access-Jwt-Assertion 検証を追加(テスト込み)
  - 実施するのは「LAN からの :8080 直アクセスも塞ぎたい」場合のみ。既定ではアプリ無改造を維持
  - File: src/main/kotlin/dev/rockyh/rsswatch/shared/config/AccessJwtFilter.kt ほか
  - Test: 有効 JWT で通過・無し/不正 JWT で 401 を MockMvc + JWKS スタブでテストしてから実装(TDD)
  - Purpose: 防御多層化(Access を経由しないリクエストの拒否)
  - _Requirements: Non-Functional(Security)_
