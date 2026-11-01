// Reads an item's name and artist from its own service, with the key-less endpoints the app
// uses: YouTube and SoundCloud oEmbed, iTunes Lookup, Deezer's API and public page metadata.
// Ported from LinkResolver.kt. Null when it can't be read; the link then opens untouched.

import { Apis, YOUTUBE_MUSIC_BROWSE_URL, YOUTUBE_MUSIC_NEXT_URL, soundCloudTrack } from "./apis.ts";
import type { Fetcher } from "./http.ts";
import { Unreachable, parseJson } from "./http.ts";
import { str } from "./json.ts";
import type { MusicLink } from "./links.ts";
import * as parse from "./parsers.ts";
import type { Metadata } from "./services.ts";

function oEmbed(endpoint: string, link: MusicLink): string {
  return `${endpoint}?${new URLSearchParams({ format: "json", url: link.url })}`;
}

function metadataUrl(link: MusicLink): string {
  switch (link.service) {
    case "youtube":
    case "youtubemusic":
      return oEmbed("https://www.youtube.com/oembed", link);
    case "soundcloud":
      return oEmbed("https://soundcloud.com/oembed", link);
    case "applemusic":
      return `https://itunes.apple.com/lookup?${new URLSearchParams({ id: link.id, country: link.region ?? "us" })}`;
    case "deezer":
      return `https://api.deezer.com/${link.type}/${link.id}`;
    default:
      return link.url;
  }
}

function isYouTubeVideo(link: MusicLink): boolean {
  return (link.service === "youtube" || link.service === "youtubemusic") && link.type === "track";
}

/** The video [id] as YouTube Music names it, for a video its owner keeps from being embedded elsewhere. */
async function youtubeMusicVideo(apis: Apis, id: string): Promise<Metadata | null> {
  try {
    return parse.youtubeMusicVideo(await apis.youtubeMusic(YOUTUBE_MUSIC_NEXT_URL, "videoId", id), id);
  } catch (error) {
    if (error instanceof Unreachable) return null;
    throw error;
  }
}

/** The SoundCloud upload at [url] as SoundCloud's own API has it, for one its owner keeps on SoundCloud. */
async function soundCloudUpload(apis: Apis, url: string): Promise<Metadata | null> {
  try {
    const item = await apis.soundCloudItem(url);
    return item !== undefined && str(item["kind"]) === "track" ? soundCloudTrack(item) : null;
  } catch (error) {
    if (error instanceof Unreachable) return null;
    throw error;
  }
}

/** What [link] is, by its service; null when the service doesn't say. */
export async function resolveMetadata(link: MusicLink, fetcher: Fetcher, apis: Apis): Promise<Metadata | null> {
  try {
    if (link.service === "youtubemusic" && (link.type === "album" || link.type === "artist")) {
      return parse.youtubeMusicPage(await apis.youtubeMusic(YOUTUBE_MUSIC_BROWSE_URL, "browseId", link.id), link.type);
    }
    const reply = await fetcher(metadataUrl(link));
    // The owner keeps it from being embedded elsewhere, as many labels' videos are, or on SoundCloud.
    if ((reply.status === 401 || reply.status === 403) && isYouTubeVideo(link)) {
      const named = await youtubeMusicVideo(apis, link.id);
      if (named !== null) return named;
    }
    if ((reply.status === 401 || reply.status === 403) && link.service === "soundcloud" && link.type === "track") {
      const named = await soundCloudUpload(apis, link.url);
      if (named !== null) return named;
    }
    if (!reply.ok) return null;
    switch (link.service) {
      case "spotify":
        return parse.spotify(reply.text, link.type);
      case "youtube":
      case "youtubemusic":
        return parse.youtube(parseJson(reply));
      case "applemusic":
        return parse.appleMusic(parseJson(reply), link.type);
      case "deezer":
        return parse.deezer(parseJson(reply), link.type);
      case "tidal":
        return parse.tidal(reply.text, link.type);
      case "soundcloud":
        return parse.soundCloud(parseJson(reply), link.type);
      case "amazonmusic":
        return null;
    }
  } catch (error) {
    if (error instanceof Unreachable) return null;
    throw error;
  }
}

