# rss-watch desktop(Electron シェル)

Web UI(`static/index.html`)をそのままデスクトップアプリとして表示し、記事リンクを
**新規タブではなくウィンドウ内のオーバーレイ(`WebContentsView`)** で開くラッパー。

オーバーレイはトップレベルナビゲーション扱いのため、`X-Frame-Options` /
CSP `frame-ancestors` で iframe 埋め込みを拒否するサイト(Zenn / Qiita / Hacker News 等)も
そのまま表示できる(issue #42)。

## 使い方

```bash
cd desktop
npm install
npm start
```

- 接続先はデフォルトで `https://rss-watch.rocky-ha.com/`。`RSS_WATCH_URL` 環境変数で変更可
  (例: ローカル起動中のアプリなら `RSS_WATCH_URL=http://localhost:8080 npm start`)
- 記事リンク(`target="_blank"`)をクリックするとオーバーレイで開く
- 閉じる: ツールバーの「✕ 閉じる」または Esc
- 「ブラウザで開く ↗」で通常ブラウザに切り替え

## テスト

```bash
npm test   # node --test(オーバーレイ領域計算・URL 判定の単体テスト)
```

## 動作確認(スモーク)

指定 URL をオーバーレイで開き、レンダリング結果を PNG に保存して終了する:

```bash
SMOKE_URL=https://zenn.dev SMOKE_OUT=/tmp/smoke.png npm start
```

## 備考

- Cloudflare Access 配下のため、初回起動時はウィンドウ内で Access のログインが必要。
  セッション Cookie は Electron のプロファイルに保持される
- オーバーレイ内のリンクはオーバーレイ内で遷移する(新規ウィンドウは開かない)
