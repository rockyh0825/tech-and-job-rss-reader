function computeOverlayBounds(contentSize, toolbarHeight) {
  if (toolbarHeight < 0) {
    throw new RangeError(`toolbarHeight must be >= 0: ${toolbarHeight}`);
  }
  const { width, height } = contentSize;
  const actualToolbarHeight = Math.min(toolbarHeight, height);
  return {
    toolbar: { x: 0, y: 0, width, height: actualToolbarHeight },
    article: {
      x: 0,
      y: actualToolbarHeight,
      width,
      height: Math.max(0, height - actualToolbarHeight),
    },
  };
}

function isOverlayUrl(url) {
  if (typeof url !== "string") return false;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  return parsed.protocol === "http:" || parsed.protocol === "https:";
}

// メインウィンドウの同タブ遷移(will-navigate)を許可するか判定する。
// 許可対象: START_URL と同一 origin(scheme/host/port 完全一致)/
// Cloudflare Access(https の *.cloudflareaccess.com)/
// Google IdP(https の accounts.google.com)/ extraHosts で明示追加されたホスト(https 必須)。
function isAllowedMainNavigation(url, startUrl, extraHosts = []) {
  if (!isOverlayUrl(url)) return false;
  const parsed = new URL(url);

  try {
    if (parsed.origin === new URL(startUrl).origin) return true;
  } catch {
    // startUrl が不正でも他の許可ホスト判定は続行する
  }

  // START_URL 以外の許可ホストはログイン用途のため https のみ許可
  // (http ダウングレードによるすり抜けを塞ぐ)
  if (parsed.protocol !== "https:") return false;
  const hostname = parsed.hostname.toLowerCase();

  if (hostname.endsWith(".cloudflareaccess.com")) return true;
  if (hostname === "accounts.google.com") return true;

  return extraHosts
    .map((host) => host.trim().toLowerCase())
    .filter((host) => host !== "")
    .includes(hostname);
}

module.exports = { computeOverlayBounds, isOverlayUrl, isAllowedMainNavigation };
