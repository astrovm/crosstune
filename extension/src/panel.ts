// The lyrics panel: the words of what's playing. Chrome, Edge and Brave show it in their side panel,
// Firefox in its sidebar. It asks the lyrics sites only while it's open and only for the song
// playing, so nothing is looked up until someone wants to read it.

import { HOST_PERMISSIONS } from "./hosts.ts";
import { realFetcher } from "./http.ts";
import { findLyrics, type Lyrics } from "./lyrics.ts";
import { activeLine, follows, viewOf, type View } from "./panelview.ts";
import { POSITION_MESSAGE, currentSong, songsIn, type Position, type TabSong } from "./song.ts";

const REQUEST_TIMEOUT_MS = 5_000;
const POSITION_MS = 300;
/** Someone scrolling is reading somewhere else: the words wait this long before following again. */
const SCROLL_PAUSE_MS = 5_000;
/** The words of songs heard lately, so going back to one doesn't ask again. */
const REMEMBERED_SONGS = 30;

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (found === null) throw new Error(`Missing #${id}`);
  return found as T;
}

const say = (key: string) => chrome.i18n.getMessage(key);

for (const node of document.querySelectorAll<HTMLElement>("[data-i18n]")) node.textContent = say(node.dataset["i18n"] ?? "");
for (const node of document.querySelectorAll<HTMLElement>("[data-i18n-label]")) {
  const label = say(node.dataset["i18nLabel"] ?? "");
  node.setAttribute("aria-label", label);
  node.title = label;
}
document.documentElement.lang = chrome.i18n.getUILanguage();

const nodes = {
  song: element("song"),
  title: element("title"),
  artist: element("artist"),
  words: element("words"),
  state: element("state"),
  scroller: element("scroller"),
  skeleton: element("skeleton"),
  message: element("message"),
  action: element<HTMLButtonElement>("action"),
};

const icons: Partial<Record<View, HTMLElement>> = {
  access: element("icon-problem"),
  noSong: element("icon-note"),
  instrumental: element("icon-note"),
  none: element("icon-none"),
  offline: element("icon-offline"),
  unavailable: element("icon-problem"),
};

const messages: Partial<Record<View, string>> = {
  access: "optionsAccess",
  noSong: "lyricsNoSong",
  instrumental: "lyricsInstrumental",
  none: "lyricsNone",
  offline: "lyricsOffline",
  unavailable: "lyricsUnavailable",
};

const actions: Partial<Record<View, string>> = { access: "optionsAllow", unavailable: "lyricsRetry" };

let access = true;
let song: TabSong | null = null;
let lyrics: Lyrics | null = null;
/** The look-up under way: a new song replaces it, and its late answer is dropped. */
let looking: AbortController | undefined;
let drawn = "";
let lit = -1;
let following = false;
let scrolledAt = 0;
const remembered = new Map<string, Lyrics>();

const songKey = (of: TabSong) => JSON.stringify([of.title, of.artist]);

function drawWords(): void {
  if (lyrics?.kind !== "found") return;
  const key = `${song === null ? "" : songKey(song)}\n${lyrics.source}`;
  if (drawn === key) return;
  drawn = key;
  lit = -1;
  const texts = lyrics.lines.length > 0 ? lyrics.lines.map((line) => line.text) : lyrics.words.split("\n");
  nodes.words.replaceChildren(
    ...texts.map((text) => {
      const line = document.createElement("p");
      line.textContent = text;
      if (text === "") line.className = "gap";
      return line;
    }),
  );
  nodes.scroller.scrollTop = 0;
}

function draw(): void {
  const view = viewOf({ access, online: navigator.onLine, song, lyrics });
  nodes.song.hidden = song === null;
  nodes.title.textContent = song?.title ?? "";
  nodes.artist.textContent = song?.artist ?? "";
  nodes.words.hidden = view !== "words";
  nodes.state.hidden = view === "words";
  nodes.state.setAttribute("aria-busy", String(view === "loading"));
  nodes.skeleton.hidden = view !== "loading";
  for (const icon of Object.values(icons)) icon.hidden = true;
  const icon = icons[view];
  if (icon !== undefined) icon.hidden = false;
  const message = view === "loading" ? "" : say(messages[view] ?? "");
  nodes.message.textContent = message;
  nodes.message.hidden = message === "";
  if (view === "loading") nodes.state.setAttribute("aria-label", say("lyricsLoading"));
  else nodes.state.removeAttribute("aria-label");
  const action = actions[view];
  nodes.action.hidden = action === undefined;
  if (action !== undefined) nodes.action.textContent = say(action);
  nodes.action.dataset["view"] = view;
  if (view === "words") drawWords();
  else drawn = "";
}

async function look(forSong: TabSong): Promise<void> {
  looking?.abort();
  const key = songKey(forSong);
  const known = remembered.get(key);
  if (known !== undefined) {
    lyrics = known;
    draw();
    return;
  }
  lyrics = null;
  draw();
  if (!navigator.onLine) return;
  const mine = new AbortController();
  looking = mine;
  const answer = await findLyrics(forSong, realFetcher(REQUEST_TIMEOUT_MS, mine.signal));
  if (mine.signal.aborted) return;
  // A site that wouldn't answer may yet: that isn't kept.
  if (answer.kind !== "unavailable") {
    remembered.set(key, answer);
    if (remembered.size > REMEMBERED_SONGS) remembered.delete(remembered.keys().next().value ?? "");
  }
  lyrics = answer;
  draw();
}

function retry(): void {
  if (song !== null) void look(song);
}

async function readSong(): Promise<void> {
  const next = currentSong(songsIn(await chrome.storage.session.get(null)));
  const sameSong = next !== null && song !== null && songKey(next) === songKey(song);
  song = next;
  if (next === null) {
    looking?.abort();
    lyrics = null;
    draw();
  } else if (!sameSong) void look(next);
  else draw();
}

async function askPosition(): Promise<Position | null> {
  if (song === null) return null;
  try {
    return ((await chrome.tabs.sendMessage(song.tabId, { type: POSITION_MESSAGE })) as Position | null | undefined) ?? null;
  } catch {
    return null;
  }
}

async function followMusic(): Promise<void> {
  if (document.visibilityState !== "visible" || lyrics?.kind !== "found" || lyrics.lines.length === 0) return;
  const position = await askPosition();
  const nowFollowing = lyrics?.kind === "found" && follows(lyrics, position);
  if (nowFollowing !== following) {
    following = nowFollowing;
    nodes.words.classList.toggle("following", following);
    if (!following) nodes.words.children[lit]?.classList.remove("now");
    if (!following) lit = -1;
  }
  if (!following || position === null || lyrics?.kind !== "found") return;
  const now = activeLine(lyrics.lines, position.positionMs);
  if (now === lit) return;
  nodes.words.children[lit]?.classList.remove("now");
  lit = now;
  const line = nodes.words.children[now];
  line?.classList.add("now");
  if (line !== undefined && Date.now() - scrolledAt > SCROLL_PAUSE_MS) {
    const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;
    line.scrollIntoView({ block: "center", behavior: calm ? "auto" : "smooth" });
  }
}

async function readAccess(): Promise<void> {
  access = await chrome.permissions.contains({ origins: HOST_PERMISSIONS });
  draw();
}

element("settings").addEventListener("click", () => void chrome.runtime.openOptionsPage());
nodes.action.addEventListener("click", () => {
  if (nodes.action.dataset["view"] === "access") void chrome.permissions.request({ origins: HOST_PERMISSIONS }).then(readAccess);
  else retry();
});
for (const event of ["wheel", "touchmove", "keydown"]) nodes.scroller.addEventListener(event, () => { scrolledAt = Date.now(); }, { passive: true });
window.addEventListener("online", retry);
window.addEventListener("offline", draw);
chrome.storage.onChanged.addListener((_changes, area) => {
  if (area === "session") void readSong();
});
chrome.permissions.onAdded?.addListener(() => void readAccess());
chrome.permissions.onRemoved?.addListener(() => void readAccess());
setInterval(() => void followMusic(), POSITION_MS);

void readAccess().then(readSong);
