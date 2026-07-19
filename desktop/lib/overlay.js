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

module.exports = { computeOverlayBounds, isOverlayUrl };
