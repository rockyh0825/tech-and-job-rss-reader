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
// 許可対象: START_URL と同一ホスト / Cloudflare Access(*.cloudflareaccess.com)/
// Google IdP(accounts.google.com)/ extraHosts で明示追加されたホスト。
function isAllowedMainNavigation(url, startUrl, extraHosts = []) {
  if (!isOverlayUrl(url)) return false;
  const hostname = new URL(url).hostname.toLowerCase();

  try {
    if (hostname === new URL(startUrl).hostname.toLowerCase()) return true;
  } catch {
    // startUrl が不正でも他の許可ホスト判定は続行する
  }

  if (hostname.endsWith(".cloudflareaccess.com")) return true;
  if (hostname === "accounts.google.com") return true;

  return extraHosts
    .map((host) => host.trim().toLowerCase())
    .filter((host) => host !== "")
    .includes(hostname);
}

module.exports = { computeOverlayBounds, isOverlayUrl, isAllowedMainNavigation };
