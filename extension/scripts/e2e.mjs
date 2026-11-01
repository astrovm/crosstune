// Loads the built Chrome extension into headless Chromium and clicks a Spotify song link on a
// local page; it should end up on that song on YouTube Music. Needs the internet, like the extension.
//   npm run build && node scripts/e2e.mjs

import { chromium } from "playwright";
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const extension = join(dirname(fileURLToPath(import.meta.url)), "..", "dist", "chrome");
const SPOTIFY_SONG = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";

const server = createServer((_, response) => {
  response.setHeader("content-type", "text/html");
  response.end(`<!doctype html><title>Test page</title><a id="song" href="${SPOTIFY_SONG}">A Spotify song</a>`);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const page_url = `http://127.0.0.1:${server.address().port}/`;

const profile = await mkdtemp(join(tmpdir(), "crosstune-e2e-"));
const context = await chromium.launchPersistentContext(profile, {
  channel: "chromium",
  args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
});
let failed = false;
try {
  const page = await context.newPage();
  await page.goto(page_url);
  console.log(`page:    ${page.url()}`);
  console.log(`click:   ${SPOTIFY_SONG}`);
  await page.click("#song");
  try {
    await page.waitForURL(/^https:\/\/music\.youtube\.com\/watch\?v=/, { timeout: 20_000 });
  } catch {
    failed = true;
  }
  console.log(`landed:  ${page.url()}`);
  if (failed) console.log("FAIL: the tab did not end on a YouTube Music song");
  else console.log("OK: the Spotify song link ended on YouTube Music");
  if (!failed) {
    // Back lands on the Spotify link again, and has to stay there instead of being sent away once more.
    await page.goBack({ waitUntil: "commit" });
    await page.waitForTimeout(4_000);
    console.log(`back:    ${page.url()}`);
    if (!page.url().startsWith("https://open.spotify.com/")) {
      failed = true;
      console.log("FAIL: Back did not stay on the Spotify link");
    } else console.log("OK: Back stays on the Spotify link");
  }
} finally {
  await context.close();
  server.close();
  await rm(profile, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
