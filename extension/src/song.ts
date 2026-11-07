// What's playing: read from what a music site tells the browser (its Media Session, the same thing
// Android's lockscreen shows), named as a song the way the app names one from a media session.

import { musicVideo, playerSong, youtubeVideo } from "./parsers.ts";
import { isOn, serviceForHost } from "./services.ts";

export interface SiteMetadata {
  title: string;
  artist: string;
  album: string;
}

export interface SongName {
  title: string;
  artist: string;
}

/** What a tab says it plays. */
export interface TabSong extends SongName {
  tabId: number;
  /** When the tab last said anything changed. */
  at: number;
  playing: boolean;
  /** How long the recording lasts, when the page's player says. */
  lengthMs?: number;
  /** The video playing, on YouTube's two sites. */
  videoId?: string;
}

/** What a tab's page says it's doing, for the lines to follow; null when it's not saying. */
export interface Position {
  positionMs: number;
  durationMs?: number;
  playing: boolean;
}

/** The song a site's [metadata] names, or null for anything that isn't one, whose name then goes nowhere. */
export function songFrom(host: string, metadata: SiteMetadata): SongName | null {
  const { title, artist, album } = metadata;
  if (title.trim() === "" || artist.trim() === "") return null;
  const service = serviceForHost(host);
  const named =
    service === "youtube"
      ? musicVideo(title, artist)
      : // An album says it's a release, named as it is. Without one these two play people's uploads too.
        (service === "youtubemusic" || service === "soundcloud") && album === ""
        ? youtubeVideo(title, artist)
        : playerSong(title, artist);
  return named === null ? null : { title: named.title, artist: named.artist };
}

/** The song to show: the one playing, the latest to start when more than one is; else the last one heard. */
export function currentSong(songs: readonly TabSong[]): TabSong | null {
  const newest = (from: readonly TabSong[]) => from.reduce<TabSong | null>((best, song) => (best === null || song.at > best.at ? song : best), null);
  return newest(songs.filter((song) => song.playing)) ?? newest(songs);
}

const TAB_KEY = "tab:";

/** Where in the browser's session storage a tab's song is kept: one key each, so tabs can't overwrite one another. */
/** The video a YouTube address is showing, which is what its words are looked up by. */
export function videoIdOf(host: string, search: string): string | undefined {
  if (!isOn(host, "youtube.com")) return undefined;
  return new URLSearchParams(search).get("v") ?? undefined;
}

export function tabKey(tabId: number): string {
  return `${TAB_KEY}${tabId}`;
}

/** The songs among everything kept in the session. */
export function songsIn(kept: Record<string, unknown>): TabSong[] {
  return Object.entries(kept).flatMap(([key, value]) => (key.startsWith(TAB_KEY) ? [value as TabSong] : []));
}

export const SONG_MESSAGE = "crosstune-song";
export const POSITION_MESSAGE = "crosstune-position";

/** What the content script tells the background page: a song, or null when its tab has none. */
export interface SongMessage {
  type: typeof SONG_MESSAGE;
  song: (SongName & { playing: boolean; lengthMs?: number; videoId?: string }) | null;
}
