// Firefox's turn of scripts/e2e.mjs: installs the built Firefox extension, as a temporary add-on, into
// headless Firefox through geckodriver, and clicks a Spotify song link on a local page; it should end
// up on that song on YouTube Music, and Back should stay on the link. Playwright can't load extensions
// into Firefox, so this speaks WebDriver to geckodriver itself. Needs the internet, like the extension.
//   npm run build && node scripts/e2e-firefox.mjs
// GECKODRIVER and FIREFOX point at other copies; by default, the ones on the PATH.

import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const extension = join(dirname(fileURLToPath(import.meta.url)), "..", "dist", "firefox");
const SPOTIFY_SONG = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";
const PORT = 4444 + Math.floor(Math.random() * 1000);

const server = createServer((_, response) => {
  response.setHeader("content-type", "text/html");
  response.end(`<!doctype html><title>Test page</title><a id="song" href="${SPOTIFY_SONG}">A Spotify song</a>`);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const pageUrl = `http://127.0.0.1:${server.address().port}/`;

// Zipped and sent whole, so a geckodriver that can't read this folder, as a snap's, still gets it.
const work = await mkdtemp(join(tmpdir(), "crosstune-e2e-firefox-"));
const xpi = join(work, "extension.xpi");
execFileSync("zip", ["-qr", xpi, "."], { cwd: extension });

const driver = spawn(process.env.GECKODRIVER ?? "geckodriver", ["--port", String(PORT)], { stdio: ["ignore", process.env.E2E_LOG ? "inherit" : "ignore", "ignore"] });
const base = `http://127.0.0.1:${PORT}`;

async function call(method, path, body) {
  const response = await fetch(`${base}${path}`, { method, headers: { "content-type": "application/json" }, body: body && JSON.stringify(body) });
  const json = await response.json();
  if (!response.ok) throw new Error(`${method} ${path}: ${JSON.stringify(json.value)}`);
  return json.value;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let session;
let failed = false;
try {
  for (let tries = 0; ; tries++) {
    try {
      await call("GET", "/status");
      break;
    } catch (error) {
      if (tries > 50) throw error;
      await sleep(200);
    }
  }
  // E2E_LOG shows Firefox's output, the extension's console included.
  const prefs = process.env.E2E_LOG ? { "devtools.console.stdout.chrome": true, "devtools.console.stdout.content": true } : {};
  const firefox = { args: ["-headless"], prefs, ...(process.env.FIREFOX ? { binary: process.env.FIREFOX } : {}) };
  session = (await call("POST", "/session", { capabilities: { alwaysMatch: { "moz:firefoxOptions": firefox } } })).sessionId;
  const at = `/session/${session}`;
  const id = await call("POST", `${at}/moz/addon/install`, { addon: (await readFile(xpi)).toString("base64"), temporary: true });
  console.log(`addon:   ${id}`);
  const url = async () => call("GET", `${at}/url`);
  async function waitFor(pattern, ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      if (pattern.test(await url())) return true;
      await sleep(250);
    }
    return false;
  }

  await call("POST", `${at}/url`, { url: pageUrl });
  console.log(`page:    ${await url()}`);
  console.log(`click:   ${SPOTIFY_SONG}`);
  const link = await call("POST", `${at}/element`, { using: "css selector", value: "#song" });
  await call("POST", `${at}/element/${Object.values(link)[0]}/click`, {});
  failed = !(await waitFor(/^https:\/\/music\.youtube\.com\/watch\?v=/, 20_000));
  console.log(`landed:  ${await url()}`);
  if (failed) console.log("FAIL: the tab did not end on a YouTube Music song");
  else console.log("OK: the Spotify song link ended on YouTube Music");
  if (!failed) {
    // Back lands on the Spotify link again, and has to stay there instead of being sent away once more.
    await call("POST", `${at}/back`, {});
    await sleep(4_000);
    console.log(`back:    ${await url()}`);
    if (!(await url()).startsWith("https://open.spotify.com/")) {
      failed = true;
      console.log("FAIL: Back did not stay on the Spotify link");
    } else console.log("OK: Back stays on the Spotify link");
  }
} finally {
  if (session) await call("DELETE", `/session/${session}`).catch(() => {});
  try {
    driver.kill();
  } catch {
    // A snap's geckodriver can't be stopped from outside its sandbox.
    console.log(`geckodriver ${driver.pid} is still running: point GECKODRIVER and FIREFOX at copies that aren't snaps`);
  }
  server.close();
  await rm(work, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
