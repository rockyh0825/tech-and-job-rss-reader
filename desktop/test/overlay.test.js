const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  computeOverlayBounds,
  isOverlayUrl,
  isAllowedMainNavigation,
} = require("../lib/overlay");

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

test("article_height_is_zero_when_height_equals_toolbar_height", () => {
  const bounds = computeOverlayBounds({ width: 500, height: 40 }, 40);

  assert.deepEqual(bounds.toolbar, { x: 0, y: 0, width: 500, height: 40 });
  assert.deepEqual(bounds.article, { x: 0, y: 40, width: 500, height: 0 });
});

test("accepts_uppercase_scheme_urls_for_overlay", () => {
  assert.equal(isOverlayUrl("HTTPS://EXAMPLE.COM/ARTICLE"), true);
  assert.equal(isOverlayUrl("HTTP://example.com/"), true);
});

const START_URL = "https://rss-watch.rocky-ha.com/";

test("allows_main_navigation_to_same_host_as_start_url", () => {
  assert.equal(
    isAllowedMainNavigation("https://rss-watch.rocky-ha.com/some/page", START_URL, []),
    true
  );
});

test("allows_main_navigation_to_cloudflare_access_subdomain", () => {
  assert.equal(
    isAllowedMainNavigation("https://myteam.cloudflareaccess.com/cdn-cgi/access/login", START_URL, []),
    true
  );
});

test("allows_main_navigation_to_google_accounts", () => {
  assert.equal(
    isAllowedMainNavigation("https://accounts.google.com/o/oauth2/auth", START_URL, []),
    true
  );
});

test("allows_main_navigation_to_extra_hosts_with_whitespace_and_case", () => {
  const extra = [" Okta.example.com ", "", "idp.example.org"];

  assert.equal(isAllowedMainNavigation("https://okta.example.com/login", START_URL, extra), true);
  assert.equal(isAllowedMainNavigation("https://idp.example.org/", START_URL, extra), true);
});

test("rejects_main_navigation_to_unlisted_hosts", () => {
  assert.equal(isAllowedMainNavigation("https://example.com/", START_URL, []), false);
  assert.equal(isAllowedMainNavigation("https://zenn.dev/foo/articles/bar", START_URL, []), false);
});

test("rejects_main_navigation_to_cloudflareaccess_lookalike_hosts", () => {
  assert.equal(
    isAllowedMainNavigation("https://evil-cloudflareaccess.com/", START_URL, []),
    false
  );
  assert.equal(
    isAllowedMainNavigation("https://cloudflareaccess.com.evil.example/", START_URL, []),
    false
  );
});

test("rejects_main_navigation_with_non_web_schemes", () => {
  assert.equal(isAllowedMainNavigation("javascript:alert(1)", START_URL, []), false);
  assert.equal(isAllowedMainNavigation("file:///etc/passwd", START_URL, []), false);
});

test("rejects_main_navigation_with_malformed_url_or_start_url", () => {
  assert.equal(isAllowedMainNavigation("not a url", START_URL, []), false);
  assert.equal(isAllowedMainNavigation(undefined, START_URL, []), false);
  assert.equal(isAllowedMainNavigation("https://example.com/", "not a url", []), false);
});

test("allows_main_navigation_when_extra_hosts_omitted", () => {
  assert.equal(isAllowedMainNavigation(START_URL, START_URL), true);
});
