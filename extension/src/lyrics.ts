// Finds a song's words. Ported from the app's LyricsFinder.kt: the same places in the same order,
// matched by the same names (LRCLIB, then YouTube Music, then NetEase, then Letras, then Genius), and the same
// "none" versus "wouldn't answer". Left out: what needs the app or a dictionary, like covers borrowing
// the original's words, Japanese in romaji, sentence case and fixing timing by listening.

import { Apis, YOUTUBE_MUSIC_BROWSE_URL, YOUTUBE_MUSIC_NEXT_URL } from "./apis.ts";
import { Unreachable, json, type Fetcher } from "./http.ts";
import { findObject, list, num, obj, objects, str, type Obj } from "./json.ts";
import { lookFor } from "./matcher.ts";
import { artistInside, bareTitle, distance, normalize, sameVersion, saysAnotherVersion, withGuests, wordsMatch } from "./names.ts";
import { decodeEntities, youtubeMusicVideo } from "./parsers.ts";

export interface Line {
  timeMs: number;
  text: string;
}

export type Source = "lrclib" | "youtube_music" | "netease" | "letras" | "genius";

export type Lyrics =
  /** The words, and [lines] with when each is sung, when they're timed for a recording [lengthMs] long. */
  | { kind: "found"; words: string; lines: Line[]; lengthMs?: number; source: Source }
  /** Everyone answered, and no one has them. */
  | { kind: "none" }
  /** The song has no words. */
  | { kind: "instrumental" }
  /** Too many wouldn't answer to say it has none. */
  | { kind: "unavailable" };

export interface Song {
  title: string;
  artist: string;
  lengthMs?: number;
  videoId?: string;
}

export interface FindOptions {
  /** How to wait before asking a busy LRCLIB again. */
  pause?: (ms: number) => Promise<void>;
}

const LRCLIB_URL = "https://lrclib.net/api/search";
const NETEASE_SEARCH_URL = "https://music.163.com/api/search/get";
const NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric";
const NETEASE_INSTRUMENTAL = "纯音乐，请欣赏";
const LETRAS_SEARCH_URL = "https://solr.sscdn.co/letras/m1/";
const LETRAS_SONG = "2";
const LETRAS_INSTRUMENTAL = "instrumental-lyric";

/** How far apart two recordings' lengths can be and still be the same one. */
const SAME_LENGTH_MS = 3_000;
/** LRCLIB often turns away many lookups in a row, and asks for this long a pause. */
const LRCLIB_TRIES = 3;
const LRCLIB_BUSY_PAUSE_MS = 1_000;

const ANDROID_MUSIC = { clientName: "ANDROID_MUSIC", clientVersion: "7.21.50", hl: "en" };
const WEB_REMIX = { clientName: "WEB_REMIX", clientVersion: "1.20240101.01.00", hl: "en" };

const stamp = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]/y;
const wordStamp = /<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g;
const offsetTag = /^\[offset:\s*([+-]?\d+)\s*]/im;
/** A site's ad among the words. */
const webAddress = /https?:\/\/|www\.|\b[a-z0-9-]+\.[a-z]{2,6}\//i;
const namesHeader = /^(?:artist|album|title)\s*:.*$/i;
/** Who wrote or made it, in Chinese, Japanese or English, which NetEase opens a song with: "作词 : …". */
const netEaseCredit = /^\s*(?:(?:作词|作詞|作曲|编曲|編曲|制作人|製作人)\s+\S|(?:作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|Lyricist|Lyrics|Composer|Arranger|Producer|OP|SP)\s*[:：])/i;
/** A credit before anything is sung: a short label, a colon of either width, and a name. "编程 : …". */
const openingCredit = /^([^:：]{1,40}?)\s*[:：]\s*\S/;
/** A credit a song ends on: a part and who played it, with either colon. "Guitar : …", "贝斯：…". */
const closingCredit = /^([^:：]{1,60})\s*[:：]\s*\S/;
/** Who sings a line, in a duet, rather than who played on it. */
const sungPart = /^(?:male|female|both|all|verse|chorus|hook|bridge|rap|intro|outro|pre-?chorus|男|女|合)(?:\s*\d+)?$/i;
/** Where it was made, which can sit among the closing credits. */
const netEaseStudio = /^(?:(?:recorded|mixed|mastered)\b[^:：]*\bat|published by)\b/i;
const instrumentalOpen = "([（【";
const instrumentalClose = ")]）】";
const lyricsPage = /"browseId":"(MPLY[^"]+)"/;
const letrasPagePatterns = [
  // A song in another language has Letras's own translation beside it: its words are the ones on the left.
  /<div class="lyric-translation-left">\s*(?:<strong>.*?<\/strong>)?(.*?)<\/div>/s,
  // What it sends a computer's browser, and what it sends a phone's.
  /<div class="lyric-original">(.*?)<\/div>/s,
  /<div class="lyric-content[^"]*">(.*?)<\/div>/s,
];
const letrasStanza = /(?:<br\s*\/?>\s*)?<\/p>\s*<p>/g;
const letrasBreak = /<br\s*\/?>/g;
/** A tag, or what's left of one where the words stop. */
const letrasTag = /<[^>]*(?:>|$)/g;
const letrasBox = /<div\b|<\/div>/g;

/** The lines of an LRC sheet, in order; a line sung more than once has a stamp for each time. */
export function parseLrc(lrc: string): Line[] {
  const offset = Number(offsetTag.exec(lrc)?.[1] ?? 0);
  const lines: Line[] = [];
  for (const raw of lrc.split("\n")) {
    const stamps: number[] = [];
    let textStart = 0;
    stamp.lastIndex = 0;
    for (let found = stamp.exec(raw); found !== null; found = stamp.exec(raw)) {
      const fraction = (found[3] ?? "").padEnd(3, "0");
      stamps.push(Number(found[1]) * 60_000 + Number(found[2]) * 1_000 + Number(fraction));
      textStart = stamp.lastIndex;
    }
    if (stamps.length === 0) continue;
    const text = raw.slice(textStart).replace(wordStamp, "").replace(/\s+/g, " ").trim();
    for (const at of stamps) lines.push({ timeMs: Math.max(0, at - offset), text });
  }
  return lines.sort((a, b) => a.timeMs - b.timeMs);
}

function withoutAds(text: string): string[] {
  return text.split("\n").filter((line) => !webAddress.test(line));
}

/** A contributor's mark for an instrumental stretch, in any lettering: "♪♪𝓜𝓾𝓼𝓲𝓬♪♪". Nothing is sung then. */
function isMusicMark(text: string): boolean {
  const letters = text.normalize("NFKC").toLowerCase().replace(/[♪♫♬♩\s]/gu, "");
  return letters === "" || (letters === "music" && text !== "Music" && /[♪♫♬♩]/u.test(text));
}

function timedLines(sheet: string): Line[] {
  return parseLrc(sheet)
    .filter((line) => !webAddress.test(line.text))
    .map((line) => (isMusicMark(line.text) ? { ...line, text: "" } : line));
}

function isTimed(lines: readonly Line[]): boolean {
  return lines.some((line) => line.text !== "");
}

/** Whether words timed for [timedFor] can follow a recording [playing] long. */
export function fitsLength(timedFor: number | undefined, playing: number | undefined): boolean {
  return timedFor === undefined || playing === undefined || Math.abs(timedFor - playing) <= SAME_LENGTH_MS;
}

function wordsFrom(lines: readonly Line[]): string {
  return lines.map((line) => line.text).filter((text) => text !== "").join("\n");
}

function withoutNamesHeader(words: string): string {
  const lines = words.split("\n");
  const header = lines.findIndex((line) => !namesHeader.test(line.trim()));
  return header >= 2 ? lines.slice(header).join("\n") : words;
}

// LRCLIB

function isExact(row: Obj, song: Song): boolean {
  return normalize(str(row["trackName"])) === normalize(song.title) && artistInside(str(row["artistName"]), song.artist);
}

function belongsTo(row: Obj, song: Song): boolean {
  const title = str(row["trackName"]);
  return (wordsMatch(title, song.title) || sameVersion(song.title, title, str(row["albumName"]))) && artistInside(str(row["artistName"]), withGuests(song.artist, song.title));
}

function lrclibAnswer(rows: Obj[], song: Song): Lyrics {
  const usable = rows.filter((row) => str(row["trackName"]).trim() !== "" && str(row["artistName"]).trim() !== "");
  const exact = usable.filter((row) => isExact(row, song));
  const named = exact.length > 0 ? exact : usable.filter((row) => belongsTo(row, song));
  // Timed copies of different lengths are different takes, so the one as long as the song comes first.
  const rowLength = (row: Obj) => (num(row["duration"]) > 0 ? Math.round(num(row["duration"]) * 1_000) : undefined);
  const gap = (row: Obj) => {
    const length = rowLength(row);
    return length === undefined || song.lengthMs === undefined || !fitsLength(length, song.lengthMs) ? Number.MAX_SAFE_INTEGER : Math.abs(length - song.lengthMs);
  };
  const ordered = [...named].sort((a, b) => gap(a) - gap(b));
  const timed = ordered.find((row) => isTimed(timedLines(str(row["syncedLyrics"]))));
  if (timed !== undefined) {
    const lines = timedLines(str(timed["syncedLyrics"]));
    const length = rowLength(timed);
    return { kind: "found", words: wordsFrom(lines), lines, ...(length === undefined ? {} : { lengthMs: length }), source: "lrclib" };
  }
  const plain = ordered.find((row) => str(row["plainLyrics"]).trim() !== "");
  if (plain !== undefined) {
    const words = withoutAds(withoutNamesHeader(str(plain["plainLyrics"]))).join("\n").trim();
    if (words !== "") return { kind: "found", words, lines: [], source: "lrclib" };
  }
  return named.some((row) => row["instrumental"] === true) ? { kind: "instrumental" } : { kind: "none" };
}

async function fromLrclib(song: Song, fetcher: Fetcher, pause: (ms: number) => Promise<void>): Promise<Lyrics> {
  const url = `${LRCLIB_URL}?${new URLSearchParams({ track_name: song.title, artist_name: song.artist })}`;
  for (let tried = 0; tried < LRCLIB_TRIES; tried++) {
    if (tried > 0) await pause(LRCLIB_BUSY_PAUSE_MS);
    try {
      const reply = await fetcher(url);
      // Its errors are objects naming themselves, so the difference between "none" and "wouldn't answer" rests on a list.
      const rows: unknown = reply.ok ? JSON.parse(reply.text) : undefined;
      if (Array.isArray(rows)) return lrclibAnswer(objects(rows), song);
    } catch (error) {
      if (!(error instanceof Unreachable || error instanceof SyntaxError)) throw error;
    }
  }
  return { kind: "unavailable" };
}

// YouTube Music

async function videoFor(song: Song, fetcher: Fetcher): Promise<string | undefined> {
  if (song.videoId !== undefined) return song.videoId;
  const apis = new Apis(fetcher, "");
  const match = await lookFor("youtubemusic", { title: song.title, artist: song.artist, type: "track", ...(song.lengthMs === undefined ? {} : { lengthMs: song.lengthMs }) }, apis);
  if (match.kind === "failed") throw new Unreachable("YouTube Music's search");
  return match.kind === "found" ? (new URL(match.url).searchParams.get("v") ?? undefined) : undefined;
}

async function ytmPost(url: string, body: unknown, fetcher: Fetcher): Promise<string> {
  const reply = await fetcher(url, json(body));
  if (!reply.ok) throw new Unreachable(`YouTube Music answered ${reply.status}`);
  return reply.text;
}

function youTubeMusicWords(page: unknown): Lyrics {
  const data = findObject(page, (found) => (Array.isArray(found["timedLyricsData"]) ? found : undefined));
  const rows = objects(data?.["timedLyricsData"]);
  // Where nothing is sung, it has a note.
  const texts = rows.map((row) => { const text = str(row["lyricLine"]).trim(); return text === "♪" ? "" : text; });
  const words = texts.filter((text) => text !== "").join("\n");
  if (words === "") return { kind: "none" };
  const starts = rows.map((row) => Number.parseInt(str(obj(row["cueRange"])?.["startTimeMilliseconds"]), 10));
  if (starts.some((at) => Number.isNaN(at))) return { kind: "found", words, lines: [], source: "youtube_music" };
  const lines = texts.map((text, at) => ({ timeMs: starts[at] ?? 0, text })).sort((a, b) => a.timeMs - b.timeMs);
  return { kind: "found", words, lines, source: "youtube_music" };
}

async function fromYouTubeMusic(song: Song, fetcher: Fetcher): Promise<Lyrics> {
  try {
    const videoId = await videoFor(song, fetcher);
    if (videoId === undefined) return { kind: "none" };
    const watching = await ytmPost(YOUTUBE_MUSIC_NEXT_URL, { context: { client: WEB_REMIX }, videoId }, fetcher);
    // A song without words has no page for them.
    const page = lyricsPage.exec(watching)?.[1];
    if (page === undefined) return { kind: "none" };
    // Only YouTube Music's app is given them timed.
    const answer = youTubeMusicWords(JSON.parse(await ytmPost(YOUTUBE_MUSIC_BROWSE_URL, { context: { client: ANDROID_MUSIC }, browseId: page }, fetcher)));
    if (answer.kind !== "found") return answer;
    // Timed for that very release, which, found by its name, may be another edit of the song.
    const lengthMs = youtubeMusicVideo(JSON.parse(watching), videoId)?.lengthMs ?? (song.videoId === undefined ? undefined : song.lengthMs);
    return lengthMs === undefined ? answer : { ...answer, lengthMs };
  } catch (error) {
    if (error instanceof Unreachable || error instanceof SyntaxError) return { kind: "unavailable" };
    throw error;
  }
}

// NetEase

function netEaseMatch(row: Obj, song: Song): boolean {
  const name = str(row["name"]);
  const album = str(obj(row["album"])?.["name"]);
  const credit = withGuests(song.artist, song.title);
  return sameVersion(song.title, name, album) && objects(row["artists"]).some((artist) => artistInside(str(artist["name"]), credit));
}

/** A line that only marks an instrumental stretch: "[Instrumental]", "（instrumental）". A line that merely says the word stays. */
function isInstrumentalMark(line: string): boolean {
  let text = line.trim();
  if (text.length >= 2) {
    const open = instrumentalOpen.indexOf(text[0] ?? "");
    const close = instrumentalClose.indexOf(text[text.length - 1] ?? "");
    if (open >= 0 && open === close) text = text.slice(1, -1).trim();
  }
  return text.toLowerCase() === "instrumental";
}

/** Whether [line] is a credit before anything is sung. A blank line counts, so credits can have one between them. */
function isOpeningCredit(line: string): boolean {
  const text = line.trim();
  if (text === "") return true;
  const label = openingCredit.exec(text)?.[1]?.trim();
  if (label === undefined) return false;
  return /\p{L}/u.test(label) && !sungPart.test(label) && label.split(" ").length <= 3;
}

/** Whether [line] names who played. A time ends on a digit, as "7" in "7:30" and "When it's 11" in "When it's 11:30". */
function isClosingCredit(line: string): boolean {
  const label = closingCredit.exec(line)?.[1]?.trim() ?? "";
  return label !== "" && /\p{L}/u.test(label) && !/\p{Nd}$/u.test(label) && !sungPart.test(label);
}

function dropOpening<T>(lines: T[], text: (line: T) => string): T[] {
  let start = 0;
  while (start < lines.length && isOpeningCredit(text(lines[start] as T))) start++;
  return lines.slice(start);
}

/** The lines before a run of at least two credits at the end. One credit there can be sung, so it stays. */
function dropClosing<T>(lines: T[], text: (line: T) => string): T[] {
  let start = lines.length;
  let credits = 0;
  for (let index = lines.length - 1; index >= 0; index--) {
    const line = text(lines[index] as T).trim();
    if (line === "") continue;
    if (isClosingCredit(line)) credits++;
    else if (!netEaseStudio.test(line)) break;
    start = index;
  }
  return credits >= 2 ? lines.slice(0, start) : lines;
}

/** [lines] without credits, by the known labels and by their shape, and without an instrumental mark. */
function withoutCreditShape<T>(lines: T[], text: (line: T) => string): T[] {
  const kept = lines.filter((line) => !netEaseCredit.test(text(line)) && !isInstrumentalMark(text(line)));
  return dropClosing(dropOpening(kept, text), text);
}

function netEaseAnswer(lrc: string, lengthMs: number | undefined): Lyrics {
  // Its note for an instrumental takes the words' place.
  if (lrc.includes(NETEASE_INSTRUMENTAL)) return { kind: "instrumental" };
  const length = lengthMs === undefined ? {} : { lengthMs };
  const timed = timedLines(lrc);
  if (timed.length > 0) {
    const lines = withoutCreditShape(timed, (line) => line.text);
    if (isTimed(lines)) return { kind: "found", words: wordsFrom(lines), lines, ...length, source: "netease" };
    // Nothing sung, and a line was only the mark: the song has no words.
    if (timed.some((line) => isInstrumentalMark(line.text))) return { kind: "instrumental" };
    return { kind: "none" };
  }
  const raw = withoutAds(lrc);
  // A line in brackets is a part's name, unless the brackets hold only the instrumental mark.
  const plain = raw.filter((line) => !line.trim().startsWith("[") || isInstrumentalMark(line));
  const lines = withoutCreditShape(plain.map((text) => ({ timeMs: 0, text })), (line) => line.text);
  const words = wordsFrom(lines);
  if (words !== "") return { kind: "found", words, lines: [], source: "netease" };
  if (raw.some((line) => isInstrumentalMark(line))) return { kind: "instrumental" };
  return { kind: "none" };
}

async function fromNetEase(song: Song, fetcher: Fetcher): Promise<Lyrics> {
  try {
    const search = await fetcher(`${NETEASE_SEARCH_URL}?${new URLSearchParams({ s: `${song.title} ${song.artist}`, type: "1", limit: "10" })}`);
    if (!search.ok) return { kind: "unavailable" };
    const result = obj(obj(JSON.parse(search.text))?.["result"]);
    if (result === undefined) return { kind: "unavailable" };
    const matching = objects(result["songs"]).filter((row) => netEaseMatch(row, song));
    // Of the same song, the recording as long as the one playing is the one timed for it.
    const chosen = matching.find((row) => song.lengthMs !== undefined && fitsLength(num(row["duration"]), song.lengthMs)) ?? matching[0];
    if (chosen === undefined) return { kind: "none" };
    const lyric = await fetcher(`${NETEASE_LYRIC_URL}?${new URLSearchParams({ id: str(chosen["id"]), lv: "1" })}`);
    if (!lyric.ok) return { kind: "unavailable" };
    const duration = num(chosen["duration"]);
    return netEaseAnswer(str(obj(obj(JSON.parse(lyric.text))?.["lrc"])?.["lyric"]), duration > 0 ? duration : undefined);
  } catch (error) {
    if (error instanceof Unreachable || error instanceof SyntaxError) return { kind: "unavailable" };
    throw error;
  }
}

// Letras

/** [page] without the ads Letras puts between a song's verses, each a box of boxes, taken out whole. */
function withoutLetrasAds(page: string): string {
  let text = page;
  for (;;) {
    const start = text.indexOf('<div id="pub_');
    if (start < 0) return text;
    let depth = 0;
    let end = -1;
    letrasBox.lastIndex = start;
    for (let tag = letrasBox.exec(text); tag !== null; tag = letrasBox.exec(text)) {
      depth += tag[0] === "</div>" ? -1 : 1;
      if (depth === 0) {
        end = tag.index + tag[0].length;
        break;
      }
    }
    if (end < 0) return text;
    text = text.slice(0, start) + text.slice(end);
  }
}

async function fromLetras(song: Song, fetcher: Fetcher): Promise<Lyrics> {
  try {
    const search = await fetcher(`${LETRAS_SEARCH_URL}?${new URLSearchParams({ q: `${song.title} ${song.artist}`, wt: "json", callback: "letras" })}`);
    if (!search.ok) return { kind: "unavailable" };
    // Answered as a call to a function, which is taken off.
    const answer: unknown = JSON.parse(search.text.slice(search.text.indexOf("(") + 1, search.text.lastIndexOf(")")));
    const docs = obj(obj(answer)?.["response"])?.["docs"];
    if (!Array.isArray(docs)) return { kind: "unavailable" };
    const credit = withGuests(song.artist, song.title);
    const doc = objects(list(docs)).find((row) => str(row["t"]) === LETRAS_SONG && sameVersion(song.title, str(row["txt"])) && artistInside(str(row["art"]), credit));
    if (doc === undefined) return { kind: "none" };
    // The address is the search's own: it sends another song when one is guessed.
    const page = await fetcher(`https://www.letras.com/${encodeURIComponent(str(doc["dns"]))}/${encodeURIComponent(str(doc["url"]))}/`);
    if (!page.ok) return { kind: "unavailable" };
    const shown = withoutLetrasAds(page.text);
    const content = letrasPagePatterns.map((pattern) => pattern.exec(shown)?.[1]).find((found) => found !== undefined);
    // An instrumental's page says so where the words would be.
    if (content?.includes(LETRAS_INSTRUMENTAL) === true) return { kind: "instrumental" };
    const words = decodeEntities((content ?? "").replace(letrasStanza, "\n\n").replace(letrasBreak, "\n").replace(letrasTag, "")).trim();
    return words === "" ? { kind: "none" } : { kind: "found", words, lines: [], source: "letras" };
  } catch (error) {
    if (error instanceof Unreachable || error instanceof SyntaxError) return { kind: "unavailable" };
    throw error;
  }
}

// Genius

const GENIUS_FUZZY_LENGTH = 7;
const GENIUS_MAX_TITLE = 256;
const geniusAlternateName = /^([^()]+)\s*\(([^()]+)\)$/;

/** The one result with its own path, or nothing when there are none or several. */
function onlyOne(rows: readonly Obj[]): Obj | undefined {
  const paths = new Set(rows.map((row) => str(row["path"])));
  return paths.size === 1 ? rows[0] : undefined;
}

/** Genius may put a Latin name after the original script, with one letter misspelled. */
function geniusAlternateTitle(song: Song, title: string): boolean {
  const parts = geniusAlternateName.exec(title);
  const native = parts?.[1] ?? "";
  if (parts === null || ![...native].some((letter) => /\p{L}/u.test(letter) && !/\p{Script=Latin}/u.test(letter))) return false;
  if (saysAnotherVersion(title, song.title, song.artist)) return false;
  const wanted = normalize(song.title);
  const alternate = normalize(parts[2] ?? "");
  if (!/^[a-z0-9]+$/.test(wanted)) return false;
  if (wanted === alternate) return true;
  return wanted.length >= GENIUS_FUZZY_LENGTH && wanted.length <= GENIUS_MAX_TITLE && distance(wanted, alternate) === 1;
}

async function fromGenius(song: Song, fetcher: Fetcher): Promise<Lyrics> {
  try {
    const search = await fetcher(`https://genius.com/api/search/multi?${new URLSearchParams({ q: `${song.title} ${song.artist}` })}`);
    if (!search.ok) return { kind: "unavailable" };
    const sections = obj(obj(JSON.parse(search.text))?.["response"])?.["sections"];
    if (!Array.isArray(sections)) return { kind: "unavailable" };
    const credit = withGuests(song.artist, song.title);
    const byArtist = objects(sections).flatMap((section) => objects(section["hits"]))
      .filter((hit) => str(hit["type"]) === "song").map((hit) => obj(hit["result"]))
      .filter((row): row is Obj => row !== undefined &&
        [str(row["artist_names"]), str(row["primary_artist_names"])].some((artist) => artist !== "" && artistInside(artist, credit)));
    const match = byArtist.find((row) => sameVersion(song.title, str(row["title"]))) ?? onlyOne(byArtist.filter((row) => geniusAlternateTitle(song, str(row["title"]))));
    if (match === undefined) return { kind: "none" };
    if (match["instrumental"] === true) return { kind: "instrumental" };
    const path = str(match["path"]);
    if (!path.startsWith("/") || path.startsWith("//") || path.includes("\\")) return { kind: "none" };
    const page = await fetcher(`https://genius.com${path}`);
    if (!page.ok) return { kind: "unavailable" };
    const boxes: string[] = [];
    const start = /<div\b[^>]*data-lyrics-container="true"[^>]*>/g;
    for (let box = start.exec(page.text); box !== null; box = start.exec(page.text)) {
      let depth = 0;
      letrasBox.lastIndex = box.index;
      let end = page.text.length;
      for (let tag = letrasBox.exec(page.text); tag !== null; tag = letrasBox.exec(page.text)) {
        depth += tag[0] === "</div>" ? -1 : 1;
        if (depth === 0) { end = tag.index; break; }
      }
      boxes.push(page.text.slice(box.index + box[0].length, end));
      start.lastIndex = end;
    }
    const text = decodeEntities(boxes.join("\n").replace(letrasBreak, "\n").replace(letrasTag, ""));
    const lines = text.split("\n").map((line) => line.trim());
    if (lines[0]?.startsWith(str(match["title"])) && lines[0].includes(" Lyrics")) lines.shift();
    const words = lines.filter((line) => !/^\[[^\]]*]$/.test(line)).join("\n").replace(/\n{3,}/g, "\n\n").trim();
    return words === "" ? { kind: "none" } : { kind: "found", words, lines: [], source: "genius" };
  } catch (error) {
    if (error instanceof Unreachable || error instanceof SyntaxError) return { kind: "unavailable" };
    throw error;
  }
}

// All together

function isFollowing(answer: Lyrics, song: Song): boolean {
  return answer.kind === "found" && answer.lines.length > 0 && fitsLength(answer.lengthMs, song.lengthMs);
}

/** Words found anywhere win, and of those, the best timed, the first among equals; then what the others said. */
function best(answers: readonly Lyrics[], song: Song): Lyrics {
  const rank = (answer: Lyrics) =>
    isFollowing(answer, song) ? 5 : answer.kind === "found" && answer.lines.length > 0 ? 4 : answer.kind === "found" ? 3 : answer.kind === "instrumental" ? 2 : answer.kind === "none" ? 1 : 0;
  const top = answers.reduce<Lyrics>((chosen, answer) => (rank(answer) > rank(chosen) ? answer : chosen), { kind: "unavailable" });
  // LRCLIB, which has most songs' words, wouldn't answer, and the others have none: it isn't known that the song has none.
  return top.kind === "none" && answers[0]?.kind === "unavailable" ? { kind: "unavailable" } : top;
}

async function underName(song: Song, fetcher: Fetcher, pause: (ms: number) => Promise<void>): Promise<Lyrics> {
  const answers: Lyrics[] = [await fromLrclib(song, fetcher, pause)];
  for (const ask of [fromYouTubeMusic, fromNetEase]) {
    if (isFollowing(answers.at(-1) as Lyrics, song)) return answers.at(-1) as Lyrics;
    answers.push(await ask(song, fetcher));
  }
  if (isFollowing(answers.at(-1) as Lyrics, song)) return answers.at(-1) as Lyrics;
  // Letras's words are untimed, so they can't better any.
  if (!answers.some((answer) => answer.kind === "found")) answers.push(await fromLetras(song, fetcher));
  if (!answers.some((answer) => answer.kind === "found")) answers.push(await fromGenius(song, fetcher));
  return best(answers, song);
}

/** What there is to show for [song]; a title with a tag is asked about again without it when nothing's found. */
export async function findLyrics(song: Song, fetcher: Fetcher, options: FindOptions = {}): Promise<Lyrics> {
  const pause = options.pause ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const first = await underName(song, fetcher, pause);
  const bare = bareTitle(song.title);
  if (first.kind === "found" || first.kind === "instrumental" || bare === song.title) return first;
  const second = await underName({ ...song, title: bare }, fetcher, pause);
  return second.kind === "found" || second.kind === "instrumental" ? second : first;
}
