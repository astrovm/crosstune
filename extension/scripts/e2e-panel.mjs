// Every state of the lyrics panel, in Chromium with the built extension: no song, looking, words that
// follow the music, an ad, no lyrics, an instrumental, sites that won't answer, offline, a video that
// isn't music, and the panel closed, in light and dark and in another language. Nothing leaves the
// computer: a page playing silence stands in for the music site and recorded answers for the lyrics
// sites, and a request nobody planned for fails the test. Screenshots land in dist/panel-shots/.
//   npm run build && node scripts/e2e-panel.mjs
// CHROME points at another Chromium, Brave or Edge, to try it there.

import { chromium } from "playwright";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const extension = join(root, "dist", "chrome");
const shots = join(root, "dist", "panel-shots");
const fixture = (name) => readFile(join(root, "test", "fixtures", name), "utf8");
const lrclibAfrica = await fixture("lrclib-search.json");

const LENGTH_S = 295;
const FIRST_LINE = "Paper boats along the river";
const SECOND_LINE = "We sang until the morning came";

/** A music site's page: it plays a silence as long as the song, and says what it plays as they do. */
const musicPage = `<!doctype html><title>Player</title><body><script>
  let audio;
  window.load = (seconds) => {
    const samples = 8000 * seconds;
    const wav = new DataView(new ArrayBuffer(44));
    const text = (at, word) => [...word].forEach((letter, i) => wav.setUint8(at + i, letter.charCodeAt(0)));
    text(0, "RIFF"); wav.setUint32(4, 36 + samples, true); text(8, "WAVEfmt "); wav.setUint32(16, 16, true); wav.setUint16(20, 1, true); wav.setUint16(22, 1, true);
    wav.setUint32(24, 8000, true); wav.setUint32(28, 8000, true); wav.setUint16(32, 1, true); wav.setUint16(34, 8, true); text(36, "data"); wav.setUint32(40, samples, true);
    audio?.remove();
    audio = document.createElement("audio");
    audio.src = URL.createObjectURL(new Blob([wav.buffer, new Uint8Array(samples).fill(128)], { type: "audio/wav" }));
    document.body.append(audio);
  };
  window.unload = () => { audio?.remove(); audio = undefined; };
  window.load(${LENGTH_S});
  window.say = (title, artist, album = "An Album") => { navigator.mediaSession.metadata = new MediaMetadata({ title, artist, album }); };
  window.ad = (showing) => document.body.classList.toggle("ad-showing", showing);
  window.go = (seconds) => { audio.currentTime = seconds; return audio.play(); };
</script>`;

/** SoundCloud's page: no media element, only a progress bar that says its second, as theirs does. */
const soundcloudPage = `<!doctype html><title>Player</title><body>
  <div class="playbackTimeline__progressWrapper" role="progressbar" aria-valuenow="0" aria-valuemax="${LENGTH_S}"></div>
  <script>
    window.say = (title, artist) => { navigator.mediaSession.metadata = new MediaMetadata({ title, artist, album: "" }); };
    // The bar moves to [seconds] and stays, so the only way past it is the time since it moved.
    window.go = (seconds) => {
      document.querySelector(".playbackTimeline__progressWrapper").setAttribute("aria-valuenow", String(seconds));
      navigator.mediaSession.playbackState = "playing";
    };
  </script>`;

const profile = await mkdtemp(join(tmpdir(), "crosstune-e2e-panel-"));
const launch = (lang, extensionArgs = [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`]) =>
  chromium.launchPersistentContext(profile, {
    ...(process.env.CHROME ? { executablePath: process.env.CHROME } : { channel: "chromium" }),
    viewport: { width: 400, height: 720 },
    ignoreDefaultArgs: ["--disable-extensions"],
    args: [...extensionArgs, "--autoplay-policy=no-user-gesture-required", `--lang=${lang}`],
  });

let context = await launch("en-US");
/** What each lyrics site is asked, and how it answers. Changed as the states need. */
let lyricsSites = new Map();
const asked = [];
const unplanned = [];

async function answer(route) {
  const request = route.request();
  const url = request.url();
  if (/^https:\/\/(music|www)\.youtube\.com\/watch/.test(url)) return route.fulfill({ contentType: "text/html", body: musicPage });
  if (/^https:\/\/soundcloud\.com\/some-uploader\//.test(url)) return route.fulfill({ contentType: "text/html", body: soundcloudPage });
  asked.push(url);
  for (const [start, reply] of lyricsSites) {
    if (url.startsWith(start)) return typeof reply === "function" ? reply(route) : route.fulfill(reply);
  }
  unplanned.push(url);
  return route.abort();
}
await context.route(/^https?:/, answer);

const nothing = () =>
  new Map([
    ["https://lrclib.net/", { contentType: "application/json", body: "[]" }],
    ["https://music.youtube.com/youtubei/v1/", { contentType: "application/json", body: "{}" }],
    ["https://music.163.com/", { contentType: "application/json", body: '{"result":{"songs":[]},"code":200}' }],
    ["https://solr.sscdn.co/", { contentType: "text/javascript", body: 'LetrasSug({"response":{"docs":[]}})' }],
    ["https://genius.com/api/search/multi", { contentType: "application/json", body: '{"response":{"sections":[]}}' }],
  ]);

async function extensionId(from) {
  const worker = from.serviceWorkers()[0] ?? (await from.waitForEvent("serviceworker"));
  return new URL(worker.url()).host;
}

async function openPanel(from) {
  const panel = await from.newPage();
  await panel.goto(`chrome-extension://${await extensionId(from)}/panel.html`);
  return panel;
}

const shown = (panel) => panel.locator("#message").innerText();

/** A state: its words, and an icon that's really drawn. */
async function waitForMessage(panel, words) {
  await panel.waitForFunction((expected) => document.getElementById("message")?.textContent === expected && !document.getElementById("state").hidden, words, { timeout: 15_000 });
  assert.equal(await panel.locator("#state > svg:visible").count(), 1, `one icon with "${words}"`);
}

async function waitForWords(panel, line) {
  await panel.waitForFunction((expected) => !document.getElementById("words").hidden && [...document.querySelectorAll("#words p")].some((p) => p.textContent === expected), line, { timeout: 15_000 });
}

async function shot(panel, name) {
  await mkdir(shots, { recursive: true });
  await panel.bringToFront();
  await panel.screenshot({ path: join(shots, `${name}.png`) });
}

/** The narrowest side panel with the largest text a browser offers: nothing runs off the side, and the tools stay big enough to tap. */
async function fitsLargest(panel, name) {
  const before = panel.viewportSize();
  await panel.setViewportSize({ width: 320, height: 568 });
  await panel.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
  const look = await panel.evaluate(() => {
    const size = (node) => node.getBoundingClientRect();
    const seen = [...document.querySelectorAll("#settings, #action, #message, #title, #artist, #words p")].filter((node) => !node.closest("[hidden]") && !node.hidden);
    return {
      sideways: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      past: seen.filter((node) => size(node).right > innerWidth + 0.5 || size(node).left < -0.5).map((node) => node.id || node.textContent),
      small: [...document.querySelectorAll("#settings, #action")].filter((node) => !node.hidden && size(node).height < 48).map((node) => node.id),
    };
  });
  await shot(panel, `${name}-largest`);
  await panel.evaluate(() => { document.documentElement.style.fontSize = ""; });
  await panel.setViewportSize(before);
  assert.deepEqual(look, { sideways: 0, past: [], small: [] }, `${name} at the largest text`);
}

async function both(panel, name) {
  await panel.emulateMedia({ colorScheme: "light" });
  await shot(panel, `${name}-light`);
  await panel.emulateMedia({ colorScheme: "dark" });
  await shot(panel, `${name}-dark`);
  await panel.emulateMedia({ colorScheme: "light" });
  await fitsLargest(panel, name);
}

const steps = [];
async function step(name, run) {
  await run();
  steps.push(name);
  console.log(`OK: ${name}`);
}

let failed = false;
try {
  const panel = await openPanel(context);

  await step("no song: it says to play one", async () => {
    await waitForMessage(panel, "Play a song on a music site");
    assert.equal(await panel.locator("#song").isVisible(), false);
    assert.deepEqual(asked, []);
    await both(panel, "no-song");
  });

  const music = await context.newPage();
  await music.goto("https://music.youtube.com/watch?v=abc");

  await step("looking: it shows it's looking, and the song's name", async () => {
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", async (route) => {
      await held;
      await route.fulfill({ contentType: "application/json", body: lrclibAfrica });
    });
    await music.evaluate(() => { window.say("Africa", "Toto"); return window.go(1); });
    await panel.locator("#skeleton").waitFor({ state: "visible", timeout: 15_000 });
    assert.equal(await panel.locator("#state").getAttribute("aria-busy"), "true");
    assert.equal(await panel.locator("#state").getAttribute("aria-label"), "Looking for the lyrics");
    assert.equal(await panel.locator("#title").innerText(), "Africa");
    assert.equal(await panel.locator("#artist").innerText(), "Toto");
    await both(panel, "loading");
    release();
  });

  await step("words: they come, and follow the music", async () => {
    await waitForWords(panel, FIRST_LINE);
    assert.ok(asked.some((url) => url.includes("track_name=Africa&artist_name=Toto")));
    assert.ok(!asked.some((url) => new URL(url).hostname === "music.163.com"), "timed words for this recording need no one else");
    await music.evaluate(() => window.go(15.4));
    await panel.bringToFront();
    await panel.waitForFunction((line) => document.querySelector("#words p.now")?.textContent === line, SECOND_LINE, { timeout: 5_000 });
    assert.ok(await panel.locator("#words").evaluate((node) => node.classList.contains("following")));
    await both(panel, "words");
  });

  await step("words: the theme's colors read in both", async () => {
    const colors = () => panel.evaluate(() => [getComputedStyle(document.body).backgroundColor, getComputedStyle(document.querySelector("#words p.now")).color]);
    await panel.emulateMedia({ colorScheme: "light" });
    const light = await colors();
    await panel.emulateMedia({ colorScheme: "dark" });
    const dark = await colors();
    await panel.emulateMedia({ colorScheme: "light" });
    assert.notDeepEqual(light, dark);
    assert.notEqual(light[0], light[1]);
    assert.notEqual(dark[0], dark[1]);
  });

  await step("an ad: it isn't looked up, and the words stay", async () => {
    const before = asked.length;
    await music.evaluate(() => { window.ad(true); window.say("Buy our soap", "A Brand"); });
    await panel.waitForTimeout(2_500);
    assert.equal(asked.length, before);
    assert.equal(await panel.locator("#title").innerText(), "Africa");
    await music.evaluate(() => { window.say("Africa", "Toto"); window.ad(false); });
  });

  await step("no lyrics: it says so, once everyone has answered", async () => {
    lyricsSites = nothing();
    await music.evaluate(() => window.say("Song Nobody Has", "Nobody"));
    await waitForMessage(panel, "No lyrics for this song");
    assert.equal(await panel.locator("#action").isVisible(), false);
    await both(panel, "no-lyrics");
  });

  await step("an instrumental: it says so", async () => {
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: JSON.stringify([{ trackName: "Interlude", artistName: "Band", instrumental: true }]) });
    await music.evaluate(() => window.say("Interlude", "Band"));
    await waitForMessage(panel, "Instrumental");
  });

  await step("sites that won't answer: it isn't called no lyrics, and Retry asks again", async () => {
    lyricsSites = new Map([...nothing().keys()].map((site) => [site, (route) => route.abort()]));
    await music.evaluate(() => window.say("Africa", "Toto"));
    // Asked and known earlier in this run, so it's another song that shows this.
    await music.evaluate(() => window.say("Another Song", "Toto"));
    await waitForMessage(panel, "Couldn't reach the lyrics sites");
    assert.equal(await panel.locator("#action").innerText(), "Retry");
    await both(panel, "unavailable");
    lyricsSites = nothing();
    await panel.locator("#action").click();
    await waitForMessage(panel, "No lyrics for this song");
  });

  await step("offline: it says so, and looks again when back", async () => {
    lyricsSites = new Map([...nothing().keys()].map((site) => [site, (route) => route.abort()]));
    await context.setOffline(true);
    await music.evaluate(() => window.say("Song While Offline", "Toto"));
    await waitForMessage(panel, "You're offline");
    await both(panel, "offline");
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: lrclibAfrica.replaceAll('"Africa"', '"Song While Offline"') });
    await context.setOffline(false);
    await waitForWords(panel, FIRST_LINE);
  });

  await step("a player slow to say how long the recording is: the words as long as it win", async () => {
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: lrclibAfrica.replaceAll('"Africa"', '"Slow Player"') });
    await music.evaluate(() => { window.unload(); window.say("Slow Player", "Toto"); });
    await waitForWords(panel, FIRST_LINE);
    await music.evaluate(() => { window.load(340); return window.go(40.6); });
    await panel.bringToFront();
    // Only the copy timed for 340 seconds has its first line there; the other's has it at 10.5.
    await panel.waitForFunction((line) => document.querySelector("#words p.now")?.textContent === line, FIRST_LINE, { timeout: 15_000 });
    await music.evaluate((seconds) => { window.load(seconds); return window.go(1); }, LENGTH_S);
  });

  await step("a site with no media element, SoundCloud: the words follow its progress bar, within the second", async () => {
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: lrclibAfrica.replaceAll('"Africa"', '"Barely Audible"') });
    // SoundCloud's own page is asked about the song too, for its cover.
    lyricsSites.set("https://soundcloud.com/oembed", { contentType: "application/json", body: "{}" });
    const sound = await context.newPage();
    await sound.goto("https://soundcloud.com/some-uploader/barely-audible");
    assert.equal(await sound.locator("audio, video").count(), 0);
    await sound.evaluate(() => { window.say("Barely Audible", "Toto"); window.go(1); });
    await waitForWords(panel, FIRST_LINE);
    await panel.bringToFront();
    await panel.waitForFunction(() => document.getElementById("words")?.classList.contains("following"), null, { timeout: 5_000 });
    // The bar says 15 and stays: the line starts at 15.5 less the panel's 0.3 s lead, so it takes the
    // time since the bar moved, which a reading in whole seconds has none of, to light it.
    await sound.evaluate(() => window.go(15));
    await panel.waitForFunction((line) => document.querySelector("#words p.now")?.textContent === line, SECOND_LINE, { timeout: 5_000 });
    assert.equal(await sound.evaluate(() => document.querySelector(".playbackTimeline__progressWrapper").getAttribute("aria-valuenow")), "15");
    await sound.close();
  });

  await step("a video that isn't music: its name goes nowhere", async () => {
    const video = await context.newPage();
    await video.goto("https://www.youtube.com/watch?v=def");
    const before = asked.length;
    await video.evaluate(() => { window.say("My cat sleeping", "Someone", ""); return window.go(1); });
    await panel.waitForTimeout(2_500);
    assert.deepEqual(asked.slice(before), []);
    await video.close();
  });

  await step("the panel closed: nothing is looked up", async () => {
    await panel.close();
    const before = asked.length;
    await music.evaluate(() => window.say("Song With The Panel Closed", "Toto"));
    await music.waitForTimeout(2_500);
    assert.deepEqual(asked.slice(before), []);
  });

  await step("reopened: it picks up what's playing, even with the worker asleep or not", async () => {
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: lrclibAfrica.replaceAll('"Africa"', '"Song With The Panel Closed"') });
    const again = await openPanel(context);
    await waitForWords(again, FIRST_LINE);
    assert.equal(await again.locator("#title").innerText(), "Song With The Panel Closed");
    await again.close();
  });

  assert.deepEqual(unplanned, [], "a request nobody planned for");

  await context.close();
  context = await launch("es");
  await context.route(/^https?:/, answer);
  await step("another language: Spanish, with voseo", async () => {
    const spanish = await openPanel(context);
    await waitForMessage(spanish, "Poné una canción en un sitio de música");
    assert.equal(await spanish.locator("#settings").getAttribute("aria-label"), "Ajustes");
    await shot(spanish, "no-song-es");
  });

  await context.close();
  context = await launch("de");
  await context.route(/^https?:/, answer);
  await step("a long language: German, every message at the largest text", async () => {
    const german = await openPanel(context);
    await waitForMessage(german, "Spiel einen Song auf einer Musikseite ab");
    for (const [key, action] of [["lyricsNoSong"], ["lyricsNone"], ["lyricsInstrumental"], ["lyricsOffline"], ["lyricsUnavailable", "lyricsRetry"], ["optionsAccess", "optionsAllow"]]) {
      await german.evaluate(([messageKey, actionKey]) => {
        document.getElementById("message").textContent = chrome.i18n.getMessage(messageKey);
        const button = document.getElementById("action");
        button.hidden = actionKey === undefined;
        if (actionKey !== undefined) button.textContent = chrome.i18n.getMessage(actionKey);
      }, [key, action]);
      await fitsLargest(german, `${key}-de`);
    }
  });

  await context.close();
  context = await launch("en-US", ["--enable-unsafe-extension-debugging"]);
  await context.route(/^https?:/, answer);
  await step("installed with music already playing: the tab is reached without a reload", async () => {
    const playing = await context.newPage();
    await playing.goto("https://music.youtube.com/watch?v=abc");
    await playing.evaluate(() => { window.say("Africa", "Toto"); return window.go(1); });
    lyricsSites = nothing();
    lyricsSites.set("https://lrclib.net/", { contentType: "application/json", body: lrclibAfrica });
    const browser = await context.browser().newBrowserCDPSession();
    const { id } = await browser.send("Extensions.loadUnpacked", { path: extension });
    const installed = await context.newPage();
    await installed.goto(`chrome-extension://${id}/panel.html`);
    await waitForWords(installed, FIRST_LINE);
    assert.equal(await installed.locator("#title").innerText(), "Africa");
  });
} catch (error) {
  failed = true;
  console.error(error);
  console.log(`FAIL after: ${steps.join("; ")}`);
} finally {
  await context.close();
  await rm(profile, { recursive: true, force: true });
}
console.log(failed ? "FAIL" : `OK: ${steps.length} states`);
process.exit(failed ? 1 : 0);
