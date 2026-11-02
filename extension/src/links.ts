// What a link points at. Ported from the app's MusicLinks.kt: the same shapes of address, one
// service at a time. Left out: Bandcamp, Audiomack, Shazam, Google's shared results and the
// Invidious and Piped sites, which the extension doesn't open.

import { isOn, type ItemType, type Service } from "./services.ts";

/** A link to one item on one service. [url] is canonical; [region] is the storefront for Apple Music. */
export interface MusicLink {
  service: Service;
  type: ItemType;
  id: string;
  url: string;
  region?: string;
}

const spotifyIdRegex = /^[A-Za-z0-9]{22}$/;
const youtubeIdRegex = /^[A-Za-z0-9_-]{11}$/;
/** Playlist IDs, e.g. PL… for user playlists or OLAK5uy_… for albums on YouTube Music. */
const youtubeListRegex = /^[A-Za-z0-9_-]{12,}$/;
const numericIdRegex = /^\d+$/;
const tidalIdRegex = /^[A-Za-z0-9-]+$/;
const spotifyUrlInPageRegex = /https:\/\/open\.spotify\.com\/(?:intl-[A-Za-z-]+\/)?(track|album|artist|playlist)\/([A-Za-z0-9]{22})/;

const ITEM_PATHS: Record<string, ItemType> = { track: "track", album: "album", artist: "artist", playlist: "playlist" };

const SHORT_LINK_HOSTS = new Set(["spotify.link", "link.deezer.com", "deezer.page.link", "dzr.page.link", "on.soundcloud.com"]);

const SOUNDCLOUD_RESERVED_PATHS = new Set([
  "discover", "search", "stream", "you", "upload", "charts", "pages", "settings", "messages",
  "notifications", "terms-of-use", "mobile", "people", "tags", "stations", "feed", "signin", "logout",
]);
const SOUNDCLOUD_ARTIST_TABS = new Set(["tracks", "albums", "sets", "reposts", "likes", "followers", "following", "popular-tracks", "comments"]);

function itemType(path: string | undefined): ItemType | undefined {
  return path === undefined ? undefined : ITEM_PATHS[path.toLowerCase()];
}

/** A short link, which has to be followed to see where it leads. */
export function isShortLink(url: URL): boolean {
  return SHORT_LINK_HOSTS.has(url.hostname) || url.hostname.endsWith(".spotify.link");
}

/** Short-link landing pages sometimes redirect with JavaScript; the target URL is still in the HTML. */
export function linkFromPage(html: string): MusicLink | null {
  const match = spotifyUrlInPageRegex.exec(html);
  const type = itemType(match?.[1]);
  return match === null || type === undefined || match[2] === undefined ? null : spotify(type, match[2]);
}

/** What [text] is a link to, or null for anything that isn't a song, album, artist or playlist of a service. */
export function linkFor(text: string): MusicLink | null {
  try {
    return fromUrl(new URL(text.trim()));
  } catch {
    return null;
  }
}

export function fromUrl(url: URL): MusicLink | null {
  if (url.protocol !== "https:" && url.protocol !== "http:") return null;
  const host = url.hostname;
  const segments = url.pathname.split("/").filter((segment) => segment !== "");
  if (isOn(host, "spotify.com")) return spotifyUrl(url, segments);
  if (host === "youtu.be") return segments[0] === undefined ? null : youtube("youtube", segments[0]);
  if (host === "music.youtube.com") return youtubeWatch("youtubemusic", url, segments);
  if (host === "youtube.com" || host === "www.youtube.com" || host === "m.youtube.com") return youtubeWatch("youtube", url, segments);
  if (host === "music.apple.com" || host === "geo.music.apple.com" || host === "itunes.apple.com") return appleMusic(url, segments);
  if (host === "deezer.com" || host === "www.deezer.com") return deezer(segments);
  if (host === "tidal.com" || host === "www.tidal.com" || host === "listen.tidal.com") return tidal(segments);
  if (host === "soundcloud.com" || host === "www.soundcloud.com" || host === "m.soundcloud.com") return soundCloud(segments);
  return null;
}

function spotifyUriOrId(value: string): MusicLink | null {
  // A bare ID is assumed to be a Spotify track, the most commonly shared item.
  if (spotifyIdRegex.test(value)) return spotify("track", value);
  if (!value.toLowerCase().startsWith("spotify:")) return null;
  const parts = value.split(":");
  const type = parts.length === 3 ? itemType(parts[1]) : undefined;
  return type === undefined || parts[2] === undefined ? null : spotify(type, parts[2]);
}

function spotifyUrl(url: URL, segments: string[]): MusicLink | null {
  const uri = url.searchParams.get("uri");
  const fromUri = uri === null ? null : spotifyUriOrId(uri);
  if (fromUri !== null) return fromUri;
  return typedItem(segments, spotify);
}

function spotify(type: ItemType, id: string): MusicLink | null {
  if (!spotifyIdRegex.test(id)) return null;
  return { service: "spotify", type, id, url: `https://open.spotify.com/${type}/${id}` };
}

function youtubeBase(service: Service): string {
  return service === "youtubemusic" ? "https://music.youtube.com" : "https://www.youtube.com";
}

function youtubeWatch(service: Service, url: URL, segments: string[]): MusicLink | null {
  const only = segments.length === 1 ? segments[0] : undefined;
  if (only === "watch") {
    const id = url.searchParams.get("v");
    return id === null ? null : youtube(service, id);
  }
  if (segments.length === 2 && (segments[0] === "shorts" || segments[0] === "live") && segments[1] !== undefined) return youtube(service, segments[1]);
  if (only === "playlist") {
    const list = url.searchParams.get("list");
    if (list === null || !youtubeListRegex.test(list)) return null;
    return { service, type: "playlist", id: list, url: `${youtubeBase(service)}/playlist?list=${list}` };
  }
  // YouTube Music's own pages for an album and an artist.
  const [kind, id] = segments;
  if (service === "youtubemusic" && segments.length === 2 && id !== undefined) {
    if (kind === "browse" && id.startsWith("MPREb_")) return { service, type: "album", id, url: `https://music.youtube.com/browse/${id}` };
    if (kind === "channel" && id.startsWith("UC")) return { service, type: "artist", id, url: `https://music.youtube.com/channel/${id}` };
  }
  return null;
}

function youtube(service: Service, id: string): MusicLink | null {
  if (!youtubeIdRegex.test(id)) return null;
  return { service, type: "track", id, url: `${youtubeBase(service)}/watch?v=${id}` };
}

/** music.apple.com/{region}/{song|album|artist|playlist}/{slug}/{id}, where album?i= is a song. */
function appleMusic(url: URL, segments: string[]): MusicLink | null {
  const first = segments[0];
  const region = first !== undefined && first.length === 2 ? first.toLowerCase() : "us";
  const typeIndex = segments.findIndex((segment) => ["song", "album", "artist", "playlist"].includes(segment));
  if (typeIndex === -1 || typeIndex === segments.length - 1) return null;
  const last = segments[segments.length - 1] ?? "";
  const id = last.startsWith("id") ? last.slice(2) : last;
  // An empty or broken "i" still leaves a valid album link.
  const i = url.searchParams.get("i");
  const trackId = i !== null && numericIdRegex.test(i) ? i : null;
  let type: ItemType;
  let itemId: string;
  switch (segments[typeIndex]) {
    case "song":
      type = "track";
      itemId = id;
      break;
    case "album":
      type = trackId === null ? "album" : "track";
      itemId = trackId ?? id;
      break;
    case "artist":
      type = "artist";
      itemId = id;
      break;
    default:
      return id.startsWith("pl.") ? { service: "applemusic", type: "playlist", id, url: canonical(url), region } : null;
  }
  if (!numericIdRegex.test(itemId)) return null;
  return { service: "applemusic", type, id: itemId, url: canonical(url), region };
}

function canonical(url: URL): string {
  const i = url.searchParams.get("i");
  const copy = new URL(url.href);
  copy.protocol = "https:";
  copy.search = "";
  copy.hash = "";
  if (i !== null && numericIdRegex.test(i)) copy.searchParams.set("i", i);
  return copy.href;
}

function deezer(segments: string[]): MusicLink | null {
  return typedItem(segments, (type, id) => (numericIdRegex.test(id) ? { service: "deezer", type, id, url: `https://www.deezer.com/${type}/${id}` } : null));
}

/** A song's link may be under its album's, "album/123/track/456": the song is what's meant. */
function tidal(segments: string[]): MusicLink | null {
  const last = segments.map((segment) => itemType(segment) !== undefined).lastIndexOf(true);
  return typedItem(segments.slice(Math.max(last, 0)), (type, id) => (tidalIdRegex.test(id) ? { service: "tidal", type, id, url: `https://tidal.com/${type}/${id}` } : null));
}

/** soundcloud.com/{artist}, /{artist}/{track} and /{artist}/sets/{playlist}. */
function soundCloud(segments: string[]): MusicLink | null {
  const artist = segments[0];
  if (artist === undefined || SOUNDCLOUD_RESERVED_PATHS.has(artist)) return null;
  let type: ItemType;
  if (segments.length === 1) type = "artist";
  else if (segments.length === 2 && SOUNDCLOUD_ARTIST_TABS.has(segments[1] ?? "")) type = "artist";
  else if (segments.length === 2) type = "track";
  else if (segments.length === 3 && segments[1] === "sets") type = "playlist";
  else return null;
  const id = (type === "artist" ? [artist] : segments).join("/");
  return { service: "soundcloud", type, id, url: `https://soundcloud.com/${id}` };
}

/** Finds "{track|album|artist|playlist}/{id}" anywhere in the path, e.g. after a locale prefix. */
function typedItem(segments: string[], build: (type: ItemType, id: string) => MusicLink | null): MusicLink | null {
  for (let index = 0; index < segments.length; index++) {
    const type = itemType(segments[index]);
    const id = segments[index + 1];
    if (type === undefined || id === undefined) continue;
    const found = build(type, id);
    if (found !== null) return found;
  }
  return null;
}

const youtubeServices: readonly Service[] = ["youtube", "youtubemusic"];

/** This YouTube or YouTube Music playlist's own page on [service], if that's one of the two. */
export function youtubePlaylistOn(link: MusicLink, service: Service): string | null {
  if (link.type !== "playlist" || !youtubeServices.includes(link.service) || !youtubeServices.includes(service)) return null;
  return `${youtubeBase(service)}/playlist?list=${link.id}`;
}

/**
 * This YouTube or YouTube Music video's own page on [service], if that's one of the two. The two
 * apps play the same videos, so one opens as itself in the other: a search for the title finds
 * another recording, or none, and never the video the user picked.
 */
export function youtubeVideoOn(link: MusicLink, service: Service): string | null {
  if (link.type !== "track" || !youtubeServices.includes(link.service) || !youtubeServices.includes(service)) return null;
  return `${youtubeBase(service)}/watch?v=${link.id}`;
}
