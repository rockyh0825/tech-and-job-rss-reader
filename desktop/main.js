const { app, BrowserWindow, WebContentsView, shell, ipcMain } = require("electron");
const path = require("node:path");
const fs = require("node:fs");
const { computeOverlayBounds, isOverlayUrl } = require("./lib/overlay");

const START_URL = process.env.RSS_WATCH_URL || "https://rss-watch.rocky-ha.com/";
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
  toolbar.webContents.loadFile(path.join(__dirname, "toolbar.html"));
  article.webContents.loadURL(url);
  article.webContents.setWindowOpenHandler(({ url: next }) => {
    if (isOverlayUrl(next)) article.webContents.loadURL(next);
    return { action: "deny" };
  });
  article.webContents.on("did-navigate", (_event, next) => {
    if (overlay) overlay.url = next;
  });
  article.webContents.on("before-input-event", (_event, input) => {
    if (input.type === "keyDown" && input.key === "Escape") closeOverlay();
  });
}

ipcMain.handle("overlay:url", () => overlay?.url ?? null);
ipcMain.on("overlay:close", () => closeOverlay());
ipcMain.on("overlay:open-external", () => {
  if (!overlay) return;
  shell.openExternal(overlay.url);
  closeOverlay();
});

function createWindow() {
  win = new BrowserWindow({ width: 1280, height: 860, title: "rss-watch" });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isOverlayUrl(url)) openOverlay(url);
    return { action: "deny" };
  });
  win.on("resize", layoutOverlay);
  win.loadURL(START_URL);
  if (process.env.SMOKE_URL) {
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
