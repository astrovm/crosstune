// Finds the same item in another service by its name, as the app's ExactMatcher does: the same
// name and one artist in common, so remixes, covers and deluxe editions don't win by rank.
// Any failure or uncertain match is "missing" or "failed", and the caller searches instead.

import {
  Apis,
  YOUTUBE_MUSIC_ALBUMS,
  YOUTUBE_MUSIC_ARTISTS,
  YOUTUBE_MUSIC_SONGS,
  columnText,
  soundCloudTrack,
  tidalTrack,
} from "./apis.ts";
import { Unreachable } from "./http.ts";
import { num, obj, objects, str, type Obj } from "./json.ts";
import { artists, sameVersion } from "./names.ts";
import { serviceFor, searchQuery, type ItemType, type Metadata, type Service } from "./services.ts";

/** What looking for something came to: found, not there, or no answer, e.g. offline, which says neither. */
export type Match = { kind: "found"; url: string } | { kind: "missing" } | { kind: "failed" };

/** How long a lookup has, as in the app. */
export const LOOKUP_TIMEOUT_MS = 5_000;
/** How far another release of a song, e.g. a remaster or a video's, may be from its length. */
const SAME_LENGTH_MS = 8_000;
/** How many of the Spotify songs ListenBrainz knows by a name are read to find the one. */
const SPOTIFY_PAGES = 6;

const YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v=";
const YOUTUBE_MUSIC_ALBUM_URL = "https://music.youtube.com/browse/";
const YOUTUBE_MUSIC_ARTIST_URL = "https://music.youtube.com/channel/";
const YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v=";

/**
 * Whether a lookup of [type] in [target] can say it isn't there, rather than that it couldn't
 * look. A playlist is someone's own and is never anywhere else; Spotify's search needs an
 * account, so only its songs are found, through ListenBrainz; Amazon Music's pages show nothing
 * without JavaScript, so it has no lookup at all.
 */
export function canMatchExactly(target: Service, type: ItemType): boolean {
  if (type === "playlist" || target === "amazonmusic") return false;
  return !(target === "spotify" && type !== "track");
}

/** Whether [name] is one of [metadata]'s artists, e.g. the account an upload is on. */
function sameArtist(metadata: Metadata, name: string): boolean {
  const wanted = artists(metadata.artist);
  return [...artists(name)].some((artist) => wanted.has(artist));
}

function matches(metadata: Metadata, name: string, artist: string, album = ""): boolean {
  if (!sameVersion(metadata.title, name, album)) return false;
  if (metadata.type === "artist") return true;
  return sameArtist(metadata, artist);
}

/**
 * The first of [named], what a search found under the song's name, that's as long as the song,
 * when its length is known, as the same name is often on an edit or another mix of it; or else
 * the first.
 */
function asLongAs<T>(metadata: Metadata, named: T[], lengthMs: (found: T) => number | undefined): T | undefined {
  const wanted = metadata.lengthMs;
  if (wanted === undefined) return named[0];
  return named.find((found) => {
    const length = lengthMs(found);
    return length !== undefined && Math.abs(length - wanted) <= SAME_LENGTH_MS;
  }) ?? named[0];
}

/** This, when it's a link to [service]: an answer that has something else where its link goes has none. */
function linkTo(url: string, service: Service): string | null {
  return url !== "" && serviceFor(url) === service ? url : null;
}

/** What [target] has for [metadata], telling a song that isn't there from one that couldn't be looked for. */
export async function lookFor(target: Service, metadata: Metadata, apis: Apis, timeoutMs = LOOKUP_TIMEOUT_MS): Promise<Match> {
  if (!canMatchExactly(target, metadata.type)) return { kind: "missing" };
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), timeoutMs);
  });
  try {
    const url = await Promise.race([lookUp(target, metadata, apis), late]);
    if (url === null) return { kind: "failed" };
    return url === undefined ? { kind: "missing" } : { kind: "found", url };
  } catch (error) {
    if (error instanceof Unreachable) return { kind: "failed" };
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function lookUp(target: Service, metadata: Metadata, apis: Apis): Promise<string | undefined> {
  switch (target) {
    case "applemusic":
      return findOnAppleMusic(metadata, apis);
    case "deezer":
      return findOnDeezer(metadata, apis);
    case "tidal":
      return findOnTidal(metadata, apis);
    case "spotify":
      return findOnSpotify(metadata, apis);
    case "soundcloud":
      return findOnSoundCloud(metadata, apis);
    case "youtubemusic":
      return findOnYouTubeMusic(metadata, apis, YOUTUBE_MUSIC_WATCH_URL);
    case "youtube":
      return findOnYouTubeMusic(metadata, apis, YOUTUBE_WATCH_URL);
    case "amazonmusic":
      return undefined;
  }
}

async function findOnAppleMusic(metadata: Metadata, apis: Apis): Promise<string | undefined> {
  const [entity, nameKey, urlKey] =
    metadata.type === "track" ? (["song", "trackName", "trackViewUrl"] as const)
    : metadata.type === "album" ? (["album", "collectionName", "collectionViewUrl"] as const)
    : (["musicArtist", "artistName", "artistLinkUrl"] as const);
  const params = new URLSearchParams({ term: searchQuery(metadata), entity, limit: "10" });
  if (apis.appleCountry !== undefined) params.set("country", apis.appleCountry);
  const answer = await apis.getJson(`https://itunes.apple.com/search?${params}`);
  const named = objects(obj(answer)?.["results"]).filter((result) => {
    // Apple marks some albums in their name, e.g. "Name - Single", which the source doesn't.
    const name = str(result[nameKey]).replace(/ - (?:Single|EP)$/, "");
    return matches(metadata, name, str(result["artistName"]));
  });
  const found = asLongAs(metadata, named, (result) => num(result["trackTimeMillis"]));
  return found === undefined ? undefined : (linkTo(str(found[urlKey]), "applemusic") ?? undefined);
}

async function findOnDeezer(metadata: Metadata, apis: Apis): Promise<string | undefined> {
  const path = metadata.type === "track" ? "search/track" : metadata.type === "album" ? "search/album" : "search/artist";
  const params = new URLSearchParams({ q: searchQuery(metadata), limit: "10" });
  const answer = obj(await apis.getJson(`https://api.deezer.com/${path}?${params}`));
  // Deezer says it's busy, e.g. over its quota, with an error, not with no results.
  if (answer !== undefined && "error" in answer) throw new Unreachable("Deezer answered with an error");
  const named = objects(answer?.["data"]).filter((result) => {
    const name = str(result["title"]) || str(result["name"]);
    const artist = str(obj(result["artist"])?.["name"]) || name;
    return matches(metadata, name, artist);
  });
  const found = asLongAs(metadata, named, (result) => num(result["duration"]) * 1000);
  return found === undefined ? undefined : (linkTo(str(found["link"]), "deezer") ?? undefined);
}

/** TIDAL's catalogue is the labels' own, so a match by name and artist is the item itself. */
async function findOnTidal(metadata: Metadata, apis: Apis): Promise<string | undefined> {
  const [kind, path] = metadata.type === "track" ? (["tracks", "track"] as const) : metadata.type === "album" ? (["albums", "album"] as const) : (["artists", "artist"] as const);
  const results = await apis.tidalSearch(kind, searchQuery(metadata));
  const named = results.filter((result) => {
    if (metadata.type === "track") {
      const song = tidalTrack(result);
      return song !== null && matches(metadata, song.title, song.artist);
    }
    if (metadata.type === "album") {
      return matches(metadata, str(result["title"]), objects(result["artists"]).map((artist) => str(artist["name"])).join(", "));
    }
    return matches(metadata, str(result["name"]), "");
  });
  const found = asLongAs(metadata, named, (result) => num(result["duration"]) * 1000);
  return found === undefined ? undefined : `https://tidal.com/browse/${path}/${num(found["id"])}`;
}

/**
 * Spotify's search needs an account, but ListenBrainz knows which Spotify songs go by a name, and
 * each song's own page says its name, artist and length, so the one that's this song is picked
 * from the first few. Only songs: ListenBrainz knows no albums or artists.
 */
async function findOnSpotify(metadata: Metadata, apis: Apis): Promise<string | undefined> {
  const ids = (await apis.spotifyIds(metadata.title, metadata.artist)).slice(0, SPOTIFY_PAGES);
  const songs = (await Promise.all(ids.map((id) => apis.spotifySong(id)))).filter((song) => song !== null);
  const named = songs.filter((song) => matches(metadata, song.metadata.title, song.metadata.artist));
  return asLongAs(metadata, named, (song) => song.metadata.lengthMs)?.url;
}

/**
 * Anyone can upload to SoundCloud under any name, so a song or album counts only when it's the
 * artist's own, or a verified account's, and an artist only when verified.
 */
async function findOnSoundCloud(metadata: Metadata, apis: Apis): Promise<string | undefined> {
  const owned = (user: Obj | undefined) => user !== undefined && (user["verified"] === true || sameArtist(metadata, str(user["username"])));
  let found: Obj | undefined;
  if (metadata.type === "track") {
    found = (await apis.soundCloudSearch("tracks", searchQuery(metadata))).find((result) => {
      const song = soundCloudTrack(result);
      return song !== null && matches(metadata, song.title, song.artist) && owned(obj(result["user"]));
    });
  } else if (metadata.type === "album") {
    found = (await apis.soundCloudSearch("albums", searchQuery(metadata))).find((result) => {
      const user = obj(result["user"]);
      return matches(metadata, str(result["title"]), str(user?.["username"])) && owned(user);
    });
  } else {
    found = (await apis.soundCloudSearch("users", searchQuery(metadata))).find((result) => result["verified"] === true && matches(metadata, str(result["username"]), ""));
  }
  return found === undefined ? undefined : (linkTo(str(found["permalink_url"]), "soundcloud") ?? undefined);
}

/** "4:46" or "1:02:03" as milliseconds, or undefined for anything else. */
function lengthMsOf(text: string): number | undefined {
  const parts = text.trim().split(":");
  if (parts.length < 2 || parts.length > 3) return undefined;
  const numbers = parts.map((part) => (/^\d+$/.test(part.trim()) ? Number(part) : Number.NaN));
  return numbers.some(Number.isNaN) ? undefined : numbers.reduce((total, part) => total * 60 + part, 0) * 1000;
}

/**
 * YouTube Music's search, filtered to songs, albums or artists. Songs are also watchable on
 * YouTube itself, which has no such filter, so [watchUrl] picks the site the video id opens on.
 */
async function findOnYouTubeMusic(metadata: Metadata, apis: Apis, watchUrl: string): Promise<string | undefined> {
  const isSong = metadata.type === "track";
  const params = isSong ? YOUTUBE_MUSIC_SONGS : metadata.type === "album" ? YOUTUBE_MUSIC_ALBUMS : YOUTUBE_MUSIC_ARTISTS;
  // Only songs play on YouTube itself, and it would just be a search for the rest.
  if (!isSong && watchUrl === YOUTUBE_WATCH_URL) return undefined;
  const items = await apis.youtubeMusicSearch(searchQuery(metadata), params);
  const found: { url: string; lengthMs: number | undefined }[] = [];
  for (const item of items) {
    const columns = objects(item["flexColumns"]).map(columnText);
    const name = columns[0] ?? "";
    // The second line is "Artist • Album • 4:46" for songs and "Album • Artist • 2012" for albums.
    const details = (columns[1] ?? "").split(" • ");
    const artist = isSong ? (details[0] ?? "") : (details[1] ?? "");
    // A song's album may say what version it is, "Song" on "… Remix".
    const album = isSong ? (details[1] ?? "") : "";
    if (!matches(metadata, name, artist === "" ? name : artist, album)) continue;
    let url: string | undefined;
    if (isSong) {
      const id = str(obj(item["playlistItemData"])?.["videoId"]);
      url = id === "" ? undefined : watchUrl + id;
    } else {
      const id = str(obj(obj(item["navigationEndpoint"])?.["browseEndpoint"])?.["browseId"]);
      url = id === "" ? undefined : (metadata.type === "album" ? YOUTUBE_MUSIC_ALBUM_URL : YOUTUBE_MUSIC_ARTIST_URL) + id;
    }
    if (url !== undefined) found.push({ url, lengthMs: lengthMsOf(details[details.length - 1] ?? "") });
  }
  // An exact match anywhere in the results beats a close one at the top.
  return asLongAs(metadata, found, (row) => row.lengthMs)?.url;
}
