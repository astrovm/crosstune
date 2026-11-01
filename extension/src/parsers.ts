// Turns each service's page, oEmbed or API answer into what the extension needs to know of an
// item. Ported from the app's MetadataParsers.kt; null when the answer is unusable. Left out:
// what only the app shows (covers, track lists, playlists) and loanword sounds, which need a
// romaji table.

import { findObject, num, obj, objects, runsText, str, musicVideoTypeIn, type Obj } from "./json.ts";
import { same, withoutEditionTag } from "./names.ts";
import type { ItemType, Metadata } from "./services.ts";

const metaTagRegex = /<meta\s[^>]*>/gi;
const titleTagRegex = /<title[^>]*>([^<]*)<\/title>/i;
const attributeRegex = /([A-Za-z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
const tidalSuffixRegex = /(^|\s)on TIDAL$/;
const entityRegex = /&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z]+);/g;
const namedEntities: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

/** Collects og:* meta tags regardless of attribute order or quote style. */
function openGraphTags(html: string): Map<string, string> {
  const tags = new Map<string, string>();
  for (const tag of html.matchAll(metaTagRegex)) {
    const attributes = new Map<string, string>();
    for (const match of tag[0].matchAll(attributeRegex)) {
      const value = match[2] ?? "";
      attributes.set((match[1] ?? "").toLowerCase(), value === "" ? (match[3] ?? "") : value);
    }
    const property = attributes.get("property");
    const content = attributes.get("content");
    if (property === undefined || content === undefined || tags.has(property)) continue;
    tags.set(property, decodeEntities(content).trim());
  }
  return tags;
}

export function decodeEntities(text: string): string {
  return text.replace(entityRegex, (whole, entity: string) => {
    let codePoint: number;
    if (/^#x/i.test(entity)) codePoint = Number.parseInt(entity.slice(2), 16);
    else if (entity.startsWith("#")) codePoint = Number.parseInt(entity.slice(1), 10);
    else return namedEntities[entity.toLowerCase()] ?? whole;
    return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : whole;
  });
}

function titleTag(html: string): string | undefined {
  const found = titleTagRegex.exec(html)?.[1];
  return found === undefined ? undefined : decodeEntities(found).trim();
}

function removeSuffix(text: string, suffix: string): string {
  return text.endsWith(suffix) ? text.slice(0, text.length - suffix.length) : text;
}

function removeSurrounding(text: string, mark: string): string {
  return text.length >= 2 * mark.length && text.startsWith(mark) && text.endsWith(mark) ? text.slice(mark.length, text.length - mark.length) : text;
}

/**
 * Spotify tracks describe themselves as "Artist · Album · Song · Year" and albums as
 * "Artist · album · Year · N songs", with a title like "Name - Album by Artist | Spotify".
 * Artist descriptions are free text, so only their title is used.
 */
export function spotify(html: string, type: ItemType): Metadata | null {
  const tags = openGraphTags(html);
  const rawTitle = removeSuffix(tags.get("og:title") ?? "", " | Spotify").trim();
  if (rawTitle === "") return null;
  const artist = type === "track" || type === "album" ? (tags.get("og:description")?.split(" · ")[0]?.trim() ?? "") : "";
  const byArtist = ` by ${artist}`;
  const unsigned = removeSuffix(rawTitle, byArtist);
  // An album's page is titled "Listen to Name - Album by Artist" now, and was "Name - Album by Artist".
  const title = type === "album" && rawTitle.endsWith(byArtist) && rawTitle.includes(" - ") ? unsigned.slice(0, unsigned.lastIndexOf(" - ")).replace(/^Listen to /, "") : rawTitle;
  // A song's page says how long it is, in seconds, which tells its versions apart.
  const seconds = Number(/<meta name="music:duration" content="(\d+)"/.exec(html)?.[1]);
  const lengthMs = type === "track" && seconds > 0 ? seconds * 1000 : undefined;
  return lengthMs === undefined ? { title, artist, type } : { title, artist, type, lengthMs };
}

// Video titles often carry release noise such as "(Official Video)" or "[Lyrics]".
const bracketedSongRegex = /^(.*?)\s*[「『]([^」』]+)[」』].*$/;
const MUSIC_TAG_WORDS = String.raw`\b(?:official|oficial|officiel|offiziell\w*|ufficiale|oficjalny|resmi|videoclip|videoclipe|clipe|audio|lyrics?|letra|visuali[sz]er|mv|m/v)\b|клип|официальн|رسمي|كليب|公式|官方|歌詞|歌词|가사|뮤직비디오`;
const videoNoiseRegex = new RegExp(String.raw`\s*[(\[][^)\]]*(?:${MUSIC_TAG_WORDS}|\b(?:video|subtitulado|sub\.?\s*esp\w*|sub\.?\s+español|hd|hq|4k)\b)[^)\]]*[)\]]`, "gi");
/** The same said without brackets at the end, "Song Official Music Video", maybe after a "|" or a dash. */
const trailingVideoNoiseRegex = /\s*(?:[|\-–]\s*)?\b(?:official\s+(?:music\s+|lyric\s+)?(?:video|audio|visuali[sz]er)|lyric\s+video|music\s+video|animated\s+video|video\s*oficial|video\s*clip(?:\s+oficial)?|audio\s+oficial)\s*$/i;
/** What a title may end on after its song: "- Lyrics |HD|", a bare "HD" or "4K". */
const trailingQualityRegex = /(?:\s*[|\-–]\s*lyrics?\s*(?:\|?\s*(?:hd|4k)\s*\|?)?|\s+(?:full\s+)?(?:hd|hq|4k))\s*$/i;
/** A subtitles tag and what follows it, "Song // Sub Español + Lyrics // Video Oficial". */
const subtitlesTagRegex = /\s*(?:\/\/|\|\|?)\s*sub\.?\s*(?:esp|español).*$/i;
/** A track number before "Artist - Song" on an album upload, "07 - Song - Artist" but not "2814 - Song". */
const trackNumberRegex = /^\d{1,2}\s*-\s+(?=.*\s-\s)/;
/** A zero-padded track number glued to the song, "06 Una señal". */
const paddedTrackRegex = /^0\d\s+/;
const danglingDashRegex = /\s+[-–]\s*$/;

/** A hyphen, or the en or em dash some apps write, each as wide. */
const DASHES = [" - ", " – ", " — "];

/** Where "Artist - Song" splits, but not at a dash inside brackets: "NOFX [Reggae & Ska - 2014 Mixtape]" is one name. */
function artistSeparator(title: string): number {
  let depth = 0;
  for (let at = 0; at < title.length; at++) {
    const letter = title[at];
    if (letter === "[" || letter === "(") depth++;
    else if (letter === "]" || letter === ")") depth = Math.max(0, depth - 1);
    if (depth === 0 && DASHES.some((dash) => title.startsWith(dash, at))) return at;
  }
  return -1;
}

const japaneseLetter = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u;

/**
 * The song, when the title says it all again in Japanese after it, artist and all: "Plastic Love
 * 竹内まりや - プラスティック・ラブ 1984" is "Plastic Love".
 */
export function beforeItInJapanese(title: string): string | null {
  const dash = artistSeparator(title);
  if (dash <= 0) return null;
  const first = [...title.slice(0, dash)];
  const artist = first.findIndex((letter) => japaneseLetter.test(letter));
  if (artist <= 0) return null;
  const rest = first.slice(artist);
  if (!rest.every((letter) => japaneseLetter.test(letter) || /\s/.test(letter) || letter === "・") || ![...title.slice(dash)].some((letter) => japaneseLetter.test(letter))) return null;
  const song = first.slice(0, artist).join("").trim();
  return /\p{L}/u.test(song) && !japaneseLetter.test(song) ? song : null;
}

/** A song from a video's title and channel, which often put "Artist - Song" in the title. */
export function youtubeVideo(title: string, channel: string): Metadata | null {
  let raw = title.replace(subtitlesTagRegex, "").replace(videoNoiseRegex, "").trim();
  // Removing one suffix can expose another: "Song Official Video HD" or "Song HD (Lyrics)".
  for (;;) {
    const previous = raw;
    raw = raw.replace(trailingQualityRegex, "").replace(trailingVideoNoiseRegex, "").replace(danglingDashRegex, "").trim();
    if (raw === previous) break;
  }
  raw = raw.replace(trackNumberRegex, "");
  if (raw === "") return null;
  const author = removeSuffix(removeSuffix(channel, " - Topic"), "VEVO").trim();
  // Japanese videos name the song in brackets after the artist, whatever follows them:
  // "YOASOBI「アイドル」 Official Music Video".
  const bracketed = bracketedSongRegex.exec(raw);
  if (bracketed !== null) {
    const artist = (bracketed[1] ?? "").trim();
    return { title: (bracketed[2] ?? "").trim(), artist: artist === "" ? author : artist, type: "track" };
  }
  // Topic channels title songs by name alone, so a dash there is part of it, e.g. "Song - Remastered 2011";
  // anywhere, a dash before only such a tag is.
  const untagged = withoutEditionTag(raw);
  const tagOnly = untagged !== raw && !untagged.includes(" - ");
  const separator = channel.endsWith(" - Topic") || tagOnly ? -1 : artistSeparator(raw);
  if (separator <= 0) return { title: raw, artist: author, type: "track" };
  const left = raw.slice(0, separator).trim();
  const right = raw.slice(separator + 3).trim();
  // "Song - Artist" when the artist is the channel and the left isn't: "Luz - Ciro y los Persas".
  if (author !== "" && same((right.split(" - ")[0] ?? "").trim(), author) && !same(left, author)) {
    return { title: left, artist: author, type: "track" };
  }
  const song = beforeItInJapanese(right) ?? right;
  return { title: removeSurrounding(song.replace(paddedTrackRegex, ""), '"').trim(), artist: left, type: "track" };
}

/** YouTube oEmbed: "Artist - Song (Official Video)" by "ArtistVEVO", or "Song" by "Artist - Topic". */
export function youtube(answer: unknown): Metadata | null {
  const json = obj(answer);
  return youtubeVideo(str(json?.["title"]), str(json?.["author_name"]));
}

/**
 * Video [id] in YouTube Music's answer for it: a song, or a label's official video, is named as
 * its artist's channel names it, with a song's length; anyone else's upload is read from its
 * title, as a YouTube link's is.
 */
export function youtubeMusicVideo(answer: unknown, id: string): Metadata | null {
  const video = findObject(answer, (found) => {
    const renderer = obj(found["playlistPanelVideoRenderer"]);
    return renderer !== undefined && str(renderer["videoId"]) === id ? renderer : undefined;
  });
  if (video === undefined) return null;
  const title = runsText(video["title"]);
  if (title === "") return null;
  const channel = (runsText(video["longBylineText"]).split(" • ")[0] ?? "").trim();
  const type = musicVideoTypeIn(video);
  const song = type === "ATV";
  const named = type === "ATV" || type === "OMV" ? youtubeVideo(title, `${channel} - Topic`) : youtubeVideo(title, channel);
  if (named === null) return null;
  const parts = runsText(video["lengthText"]).split(":").map(Number);
  const lengthMs = song && parts.length >= 2 && parts.length <= 3 && parts.every(Number.isInteger) ? parts.reduce((total, part) => total * 60 + part, 0) * 1000 : undefined;
  return lengthMs === undefined ? named : { ...named, lengthMs };
}

/** An album's or an artist's page on YouTube Music, by its header: an album's title and its artist; an artist's name. */
export function youtubeMusicPage(answer: unknown, type: ItemType): Metadata | null {
  const header =
    findObject(answer, (found) => obj(found["musicResponsiveHeaderRenderer"]) ?? obj(found["musicDetailHeaderRenderer"])) ??
    findObject(answer, (found) => obj(found["musicImmersiveHeaderRenderer"]) ?? obj(found["musicVisualHeaderRenderer"]));
  if (header === undefined) return null;
  const title = runsText(header["title"]).trim();
  if (title === "") return null;
  if (type === "artist") return { title, artist: "", type: "artist" };
  return { title, artist: runsText(header["straplineTextOne"]).trim(), type: "album" };
}

/** iTunes Lookup API result for a song, album or artist. */
export function appleMusic(answer: unknown, type: ItemType): Metadata | null {
  const result = objects(obj(answer)?.["results"])[0];
  if (result === undefined) return null;
  const artist = str(result["artistName"]);
  let found: Metadata;
  if (type === "track") {
    const length = num(result["trackTimeMillis"]);
    found = { title: str(result["trackName"]), artist, type, ...(length > 0 ? { lengthMs: length } : {}) };
  } else if (type === "album") {
    found = { title: removeSuffix(removeSuffix(str(result["collectionName"]), " - Single"), " - EP"), artist, type };
  } else {
    found = { title: artist, artist: "", type };
  }
  return found.title.trim() === "" ? null : found;
}

/**
 * A Deezer song's name, with its version in brackets, as other services write it: Deezer joins
 * them with a space, "Matador Remasterizado 2008", which reads as one name.
 */
export function deezerTitle(song: Obj): string {
  const title = str(song["title"]);
  const name = str(song["title_short"]);
  const version = str(song["title_version"]).trim();
  return version !== "" && !"([".includes(version.charAt(0)) && title === `${name} ${version}` ? `${name} (${version})` : title;
}

/** Deezer API object; errors come back as {"error": {...}} with HTTP 200. */
export function deezer(answer: unknown, type: ItemType): Metadata | null {
  const json = obj(answer);
  if (json === undefined || "error" in json) return null;
  const title = deezerTitle(json) || str(json["name"]);
  const artist = type === "track" || type === "album" ? str(obj(json["artist"])?.["name"]) : "";
  const length = num(json["duration"]);
  if (title.trim() === "") return null;
  return { title, artist, type, ...(type === "track" && length > 0 ? { lengthMs: length * 1000 } : {}) };
}

function splitBy(text: string, separator: string, type: ItemType): Metadata {
  const index = text.lastIndexOf(separator);
  return index > 0
    ? { title: text.slice(0, index).trim(), artist: text.slice(index + separator.length).trim(), type }
    : { title: text, artist: "", type };
}

/** TIDAL pages title themselves "Name by Artist on TIDAL" or "Name on TIDAL". */
export function tidal(html: string, type: ItemType): Metadata | null {
  const title = (titleTag(html) ?? "").replace(tidalSuffixRegex, "").trim();
  if (title === "") return null;
  // Only songs and albums name an artist; "Death by Stereo" is an artist, not "Death" by "Stereo".
  return type === "track" || type === "album" ? splitBy(title, " by ", type) : { title, artist: "", type };
}

/** SoundCloud oEmbed: tracks and sets are "Name by Artist", artists are just the name. */
export function soundCloud(answer: unknown, type: ItemType): Metadata | null {
  const json = obj(answer);
  const title = str(json?.["title"]).trim();
  if (title === "") return null;
  const author = str(json?.["author_name"]).trim();
  if (type === "artist") return { title: author === "" ? title : author, artist: "", type };
  const byAuthor = ` by ${author}`;
  return author !== "" && title.endsWith(byAuthor) ? { title: removeSuffix(title, byAuthor), artist: author, type } : { title, artist: author, type };
}
