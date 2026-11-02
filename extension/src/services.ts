// The music services links can be opened in, the same ones as the app's MusicService that the
// extension covers. Amazon Music can only be a destination: its pages show nothing without JavaScript.

import { withoutEditionTag } from "./names.ts";

export const SERVICES = ["spotify", "youtubemusic", "youtube", "applemusic", "deezer", "tidal", "soundcloud", "amazonmusic"] as const;

export type Service = (typeof SERVICES)[number];

export type ItemType = "track" | "album" | "artist" | "playlist";

/** What the extension knows about an item; [artist] is blank for artists. */
export interface Metadata {
  title: string;
  artist: string;
  type: ItemType;
  /** How long a song lasts, when its service says, to tell its versions apart. */
  lengthMs?: number;
}

export function isService(value: unknown): value is Service {
  return typeof value === "string" && (SERVICES as readonly string[]).includes(value);
}

export const SERVICE_NAMES: Record<Service, string> = {
  spotify: "Spotify",
  youtubemusic: "YouTube Music",
  youtube: "YouTube",
  applemusic: "Apple Music",
  deezer: "Deezer",
  tidal: "TIDAL",
  soundcloud: "SoundCloud",
  amazonmusic: "Amazon Music",
};

/** True for [domain] itself and its subdomains, but not look-alikes such as "notdeezer.com". */
export function isOn(host: string, domain: string): boolean {
  return host === domain || host.endsWith(`.${domain}`);
}

export function serviceForHost(host: string): Service | null {
  if (isOn(host, "spotify.com") || isOn(host, "spotify.link")) return "spotify";
  if (host === "music.youtube.com") return "youtubemusic";
  if (host === "youtu.be" || isOn(host, "youtube.com")) return "youtube";
  if (isOn(host, "music.apple.com")) return "applemusic";
  if (isOn(host, "deezer.com") || isOn(host, "deezer.page.link") || host === "dzr.page.link") return "deezer";
  if (isOn(host, "tidal.com")) return "tidal";
  if (isOn(host, "soundcloud.com")) return "soundcloud";
  if (isOn(host, "music.amazon.com")) return "amazonmusic";
  return null;
}

/** The service [url] is a link to, even when it isn't a song, album or artist. */
export function serviceFor(url: string): Service | null {
  try {
    return serviceForHost(new URL(url.trim()).hostname);
  } catch {
    return null;
  }
}

/** A service's own search, which opens when what's looked for isn't found in it. */
export function searchUrl(service: Service, query: string): string {
  const q = encodeURIComponent(query);
  switch (service) {
    case "spotify":
      return `https://open.spotify.com/search/${q}`;
    case "youtubemusic":
      return `https://music.youtube.com/search?q=${q}`;
    case "youtube":
      return `https://www.youtube.com/results?search_query=${q}`;
    case "applemusic":
      return `https://music.apple.com/search?term=${q}`;
    case "deezer":
      return `https://www.deezer.com/search/${q}`;
    case "tidal":
      return `https://listen.tidal.com/search?q=${q}`;
    case "soundcloud":
      return `https://soundcloud.com/search?q=${q}`;
    case "amazonmusic":
      return `https://music.amazon.com/search/${q}`;
  }
}

/** Without a tag that names no other recording, which some services' names leave out, and then find nothing. */
export function searchQuery(metadata: Metadata): string {
  return [withoutEditionTag(metadata.title), metadata.artist].filter((part) => part.trim() !== "").join(" ");
}
