const { test } = require("node:test");
const assert = require("node:assert/strict");
const { computeOverlayBounds, isOverlayUrl } = require("../lib/overlay");

test("splits_window_into_toolbar_and_article_areas", () => {
  const bounds = computeOverlayBounds({ width: 1200, height: 800 }, 40);

  assert.deepEqual(bounds.toolbar, { x: 0, y: 0, width: 1200, height: 40 });
  assert.deepEqual(bounds.article, { x: 0, y: 40, width: 1200, height: 760 });
});

test("article_height_is_zero_when_window_is_shorter_than_toolbar", () => {
  const bounds = computeOverlayBounds({ width: 300, height: 30 }, 40);

  assert.equal(bounds.toolbar.height, 30);
  assert.deepEqual(bounds.article, { x: 0, y: 30, width: 300, height: 0 });
});

test("throws_when_toolbar_height_is_negative", () => {
  assert.throws(() => computeOverlayBounds({ width: 100, height: 100 }, -1));
});

test("accepts_http_and_https_urls_for_overlay", () => {
  assert.equal(isOverlayUrl("https://zenn.dev/foo/articles/bar"), true);
  assert.equal(isOverlayUrl("http://example.com/"), true);
});

test("rejects_non_web_schemes_for_overlay", () => {
  assert.equal(isOverlayUrl("javascript:alert(1)"), false);
  assert.equal(isOverlayUrl("file:///etc/passwd"), false);
  assert.equal(isOverlayUrl("mailto:a@example.com"), false);
});

test("rejects_malformed_urls_for_overlay", () => {
  assert.equal(isOverlayUrl("not a url"), false);
  assert.equal(isOverlayUrl(""), false);
  assert.equal(isOverlayUrl(undefined), false);
});
