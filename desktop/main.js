const { app, BrowserWindow, WebContentsView, shell, ipcMain } = require("electron");
const path = require("node:path");
const fs = require("node:fs");
const {
  computeOverlayBounds,
  isOverlayUrl,
  isAllowedMainNavigation,
} = require("./lib/overlay");

const START_URL = process.env.RSS_WATCH_URL || "https://rss-watch.rocky-ha.com/";
const EXTRA_ALLOWED_HOSTS = (process.env.RSS_WATCH_ALLOWED_HOSTS ?? "").split(",");
const TOOLBAR_HEIGHT = 40;

let win = null;
let overlay = null;

function layoutOverlay() {
  if (!win || !overlay) return;
  const [width, height] = win.getContentSize();
  const bounds = computeOverlayBounds({ width, height }, TOOLBAR_HEIGHT);
  overlay.toolbar.setBounds(bounds.toolbar);
  overlay.article.setBounds(bounds.article);
}

function closeOverlay() {
  if (!win || !overlay) return;
  const { toolbar, article } = overlay;
  overlay = null;
  win.contentView.removeChildView(toolbar);
  win.contentView.removeChildView(article);
  toolbar.webContents.close();
  article.webContents.close();
}

function openOverlay(url) {
  closeOverlay();
  const toolbar = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, "toolbar-preload.js") },
  });
  const article = new WebContentsView();
  overlay = { toolbar, article, url };
  win.contentView.addChildView(article);
  win.contentView.addChildView(toolbar);
  layoutOverlay();
  toolbar.webContents
    .loadFile(path.join(__dirname, "toolbar.html"))
    .catch((err) => console.error("overlay: toolbar の読み込みに失敗", err));
  article.webContents
    .loadURL(url)
    .catch((err) => console.error(`overlay: 記事の読み込みに失敗 (${url})`, err));
  article.webContents.setWindowOpenHandler(({ url: next }) => {
    if (isOverlayUrl(next)) {
      article.webContents
        .loadURL(next)
        .catch((err) => console.error(`overlay: 記事の読み込みに失敗 (${next})`, err));
    }
    return { action: "deny" };
  });
  // 外部プロトコル(vscode:// 等)への遷移で OS ハンドラが起動しないよう、
  // オーバーレイ内も http(s) 以外への遷移はブロックする(defense in depth)
  article.webContents.on("will-navigate", (event, next) => {
    if (!isOverlayUrl(next)) event.preventDefault();
  });
  // SPA(Next.js 等)のクライアントサイド遷移では did-navigate が発火しないため、
  // did-navigate-in-page でも URL 表示を追従させる
  const syncOverlayUrl = (next) => {
    if (!overlay || overlay.article !== article) return;
    overlay.url = next;
    overlay.toolbar.webContents.send("overlay:url-changed", next);
  };
  article.webContents.on("did-navigate", (_event, next) => syncOverlayUrl(next));
  article.webContents.on("did-navigate-in-page", (_event, next, isMainFrame) => {
    if (isMainFrame) syncOverlayUrl(next);
  });
  article.webContents.on("before-input-event", (_event, input) => {
    if (input.type === "keyDown" && input.key === "Escape") closeOverlay();
  });
}

// IPC はオーバーレイのツールバー(preload 付き webContents)からのみ受け付ける
function isToolbarSender(event) {
  return overlay !== null && event.sender === overlay.toolbar.webContents;
}

ipcMain.handle("overlay:url", (event) => (isToolbarSender(event) ? overlay.url : null));
ipcMain.on("overlay:close", (event) => {
  if (isToolbarSender(event)) closeOverlay();
});
ipcMain.on("overlay:open-external", (event) => {
  if (!isToolbarSender(event)) return;
  if (isOverlayUrl(overlay.url)) shell.openExternal(overlay.url);
  closeOverlay();
});

function createWindow() {
  const smokeUrl = process.env.SMOKE_URL;
  if (smokeUrl && !isOverlayUrl(smokeUrl)) {
    console.error(`smoke: SMOKE_URL が不正です: ${smokeUrl}`);
    app.exit(1);
    return;
  }
  win = new BrowserWindow({ width: 1280, height: 860, title: "rss-watch" });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isOverlayUrl(url)) openOverlay(url);
    return { action: "deny" };
  });
  // メインウィンドウの同タブ遷移は許可ホスト(START_URL / Access ログイン / IdP /
  // RSS_WATCH_ALLOWED_HOSTS)のみ。それ以外の記事リンク等はオーバーレイで開く。
  win.webContents.on("will-navigate", (event, url) => {
    if (isAllowedMainNavigation(url, START_URL, EXTRA_ALLOWED_HOSTS)) return;
    event.preventDefault();
    if (isOverlayUrl(url)) openOverlay(url);
  });
  win.on("resize", layoutOverlay);
  win
    .loadURL(START_URL)
    .catch((err) => console.error(`main: 接続先の読み込みに失敗 (${START_URL})`, err));
  if (smokeUrl) {
    win.webContents.once("did-finish-load", runSmoke);
  }
}

// SMOKE_URL 指定時: 記事オーバーレイを自動で開いてスクリーンショットを保存し終了する。
// レンダリング確認用(CI ではなく手元での動作検証を想定)。
function runSmoke() {
  const out = process.env.SMOKE_OUT || path.join(app.getPath("temp"), "rss-watch-smoke.png");
  setTimeout(() => {
    console.error("smoke: timeout");
    app.exit(1);
  }, 30000);
  openOverlay(process.env.SMOKE_URL);
  overlay.article.webContents.once("did-finish-load", async () => {
    await new Promise((resolve) => setTimeout(resolve, 2500));
    const image = await overlay.article.webContents.capturePage();
    fs.writeFileSync(out, image.toPNG());
    console.log(`smoke: wrote ${out}`);
    app.exit(0);
  });
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
