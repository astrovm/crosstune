// Draws the stores' 1280×800 screenshot in each language into dist/screenshots/, around the
// extension's real options page: what a click on a link does, and where it's set. The stores take
// screenshots only through their dashboards, so these are uploaded by hand.
//   npm run build && node scripts/screenshot.mjs [locale ...]

import { chromium } from "playwright";
import { mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const out = join(root, "dist", "screenshots");
const locales = process.argv.length > 2 ? process.argv.slice(2) : (await readdir(join(root, "_locales"))).sort();
// The app's store icon, cropped as scripts/icons.sh does, but sharp at this size.
const icon = `data:image/png;base64,${(await readFile(join(root, "..", "fastlane", "metadata", "android", "en-US", "images", "icon.png"))).toString("base64")}`;

const escape = (text) => text.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function page(messages) {
  return `<!doctype html><meta charset="utf-8"><style>
    * { box-sizing: border-box; }
    body { margin: 0; width: 1280px; height: 800px; display: flex; align-items: center; gap: 72px; padding: 0 88px;
      background: rgb(79, 43, 217); color: white; font: 20px/1.4 system-ui, sans-serif; }
    .side { flex: 1; }
    .icon { width: 112px; height: 112px; margin: 0 0 28px; border-radius: 25px; background: center / 133% url("${icon}");
      box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.35), 0 16px 40px rgba(0, 0, 0, 0.3); }
    h1 { font-size: 64px; margin: 0 0 12px; letter-spacing: -1px; }
    p { font-size: 28px; margin: 0 0 48px; opacity: 0.92; }
    .link { display: flex; align-items: center; gap: 14px; background: rgba(255, 255, 255, 0.14); border-radius: 999px;
      padding: 14px 24px; font: 22px ui-monospace, monospace; width: fit-content; }
    .link b { width: 14px; height: 14px; border-radius: 50%; background: white; }
    .arrow { color: rgb(198, 255, 61); font-size: 44px; line-height: 1; margin: 10px 0 10px 26px; }
    .to b { background: rgb(198, 255, 61); }
    iframe { width: 468px; height: 270px; border: 0; border-radius: 20px; background: white; color-scheme: light;
      box-shadow: 0 24px 64px rgba(0, 0, 0, 0.3); }
  </style>
  <div class="side">
    <div class="icon"></div>
    <h1>${escape(messages.extName.message)}</h1>
    <p>${escape(messages.extDescription.message)}</p>
    <div class="link"><b></b>open.spotify.com/track/4cOdK2wG…</div>
    <div class="arrow">↓</div>
    <div class="link to"><b></b>music.youtube.com/watch?v=…</div>
  </div>
  <iframe src="options.html"></iframe>`;
}

await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: "chromium", args: ["--allow-file-access-from-files"] });
try {
  for (const locale of locales) {
    const messages = JSON.parse(await readFile(join(root, "_locales", locale, "messages.json"), "utf8"));
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: locale.replace("_", "-"), colorScheme: "light" });
    // The options page as the browser shows it: its words in [locale], its settings as they start.
    await context.addInitScript((messages) => {
      globalThis.chrome = {
        i18n: { getMessage: (key) => messages[key]?.message ?? "" },
        storage: { local: { get: async (defaults) => ({ ...defaults }), set: async () => {} } },
        permissions: { contains: async () => true, request: async () => true },
      };
    }, messages);
    const tab = await context.newPage();
    // A file next to the options page, as a page from elsewhere can't show it.
    const layout = join(root, "dist", "chrome", `screenshot-${locale}.html`);
    await writeFile(layout, page(messages));
    await tab.goto(pathToFileURL(layout).href);
    await rm(layout);
    await tab.frames()[1]?.waitForSelector("#service option", { state: "attached" });
    const file = join(out, `${locale}.png`);
    await tab.screenshot({ path: file });
    console.log(file);
    await context.close();
  }
} finally {
  await browser.close();
}
