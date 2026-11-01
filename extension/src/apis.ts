// The public endpoints behind each service's own website or app that need no account, as the
// Crosstune app asks them (ServiceApis.kt, ExactMatcher.kt). None is an official API, so any of
// them may stop answering; whatever uses them then falls back to the service's search.

import { Unreachable, json, parseJson, type Fetcher } from "./http.ts";
import { list, obj, objects, runsText, str, type Obj, listItems } from "./json.ts";
import { spotify } from "./parsers.ts";
import type { Metadata } from "./services.ts";

export const YOUTUBE_MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
export const YOUTUBE_MUSIC_NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
export const YOUTUBE_MUSIC_BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
const YOUTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
const YOUTUBE_MUSIC_CLIENT_VERSION = "1.20240101.01.00";
const YOUTUBE_CLIENT_VERSION = "2.20240101.00.00";

// Search filters as YouTube Music itself encodes them: the song, album and artist tabs.
export const YOUTUBE_MUSIC_SONGS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ==";
export const YOUTUBE_MUSIC_ALBUMS = "EgWKAQIYAWoKEAoQAxAEEAkQBQ==";
export const YOUTUBE_MUSIC_ARTISTS = "EgWKAQIgAWoKEAoQAxAEEAkQBQ==";

/** Songs (ATV), official music videos (OMV) and label uploads; someone's upload and podcasts aren't music. */
const MUSIC_VIDEO_TYPES = new Set(["ATV", "OMV", "OFFICIAL_SOURCE_MUSIC"]);

const TIDAL_API = "https://api.tidal.com/v1";
/** The token TIDAL's own web player asks its API with. */
const TIDAL_TOKEN = "CzET4vdadNUFQ5JU";
const SOUNDCLOUD_URL = "https://soundcloud.com/";
const SOUNDCLOUD_API = "https://api-v2.soundcloud.com";
const LISTENBRAINZ_SPOTIFY_IDS = "https://labs.api.listenbrainz.org/spotify-id-from-metadata/json";
const soundCloudScriptRegex = /<script crossorigin src="(https:\/\/a-v2\.sndcdn\.com\/assets\/[^"]+\.js)"/g;
const soundCloudIdRegex = /client_id:"([A-Za-z0-9]{32})"/;

/** A service turned a request down, e.g. with a key it no longer takes. */
export class ServiceRefused extends Unreachable {
  readonly code: number;
  constructor(code: number, host: string) {
    super(`${host} answered ${code}`);
    this.code = code;
  }
}

function query(params: Record<string, string>): string {
  return new URLSearchParams(params).toString();
}

export class Apis {
  private readonly fetcher: Fetcher;
  private readonly country: string;
  /** SoundCloud's key is read from its website's code once, and again if SoundCloud stops taking it. */
  private clientId: Promise<string> | undefined;

  constructor(fetcher: Fetcher, country: string) {
    this.fetcher = fetcher;
    this.country = country;
  }

  /** The storefront for Apple Music's search, or none when the country isn't known. */
  get appleCountry(): string | undefined {
    return this.country.length === 2 ? this.country : undefined;
  }

  /** Busy, or down, it's no answer, and says nothing about whether it has the song. */
  async getJson(url: string, headers?: Record<string, string>): Promise<unknown> {
    const reply = await this.fetcher(url, headers === undefined ? undefined : { headers });
    if (!reply.ok) throw new ServiceRefused(reply.status, new URL(reply.url).hostname);
    return parseJson(reply);
  }

  async postJson(url: string, body: unknown): Promise<unknown> {
    const reply = await this.fetcher(url, json(body));
    if (!reply.ok) throw new ServiceRefused(reply.status, new URL(reply.url).hostname);
    return parseJson(reply);
  }

  // YouTube Music

  /** What YouTube Music's [url] answers about [key] [id]. */
  async youtubeMusic(url: string, key: "videoId" | "browseId", id: string): Promise<unknown> {
    return this.postJson(url, { context: { client: { clientName: "WEB_REMIX", clientVersion: YOUTUBE_MUSIC_CLIENT_VERSION, hl: "en" } }, [key]: id });
  }

  /** The rows of YouTube Music's search for [text], filtered by [params]. */
  async youtubeMusicSearch(text: string, params: string): Promise<Obj[]> {
    const answer = await this.postJson(YOUTUBE_MUSIC_SEARCH_URL, {
      context: { client: { clientName: "WEB_REMIX", clientVersion: YOUTUBE_MUSIC_CLIENT_VERSION, hl: "en" } },
      query: text,
      params,
    });
    return listItems(answer);
  }

  /**
   * Whether a YouTube video is music: a song or an official music video, as YouTube Music counts
   * them, or someone's own upload they put under Music, like a concert they filmed; not a tutorial
   * or a vlog. Null when it can't be told, e.g. offline.
   */
  async isMusicVideo(videoId: string): Promise<boolean | null> {
    try {
      const answer = await this.postJson(YOUTUBE_MUSIC_NEXT_URL, {
        context: { client: { clientName: "WEB_REMIX", clientVersion: YOUTUBE_MUSIC_CLIENT_VERSION } },
        videoId,
      });
      const type = /"musicVideoType":"MUSIC_VIDEO_TYPE_([A-Z_]+)"/.exec(JSON.stringify(answer))?.[1];
      if (type === undefined) return null;
      return MUSIC_VIDEO_TYPES.has(type) || (await this.uploadedAsMusic(videoId));
    } catch (error) {
      if (error instanceof Unreachable) return null;
      throw error;
    }
  }

  /** Whether whoever put the video up filed it under Music, as YouTube tells whoever plays it. */
  private async uploadedAsMusic(videoId: string): Promise<boolean> {
    const answer = await this.postJson(YOUTUBE_PLAYER_URL, {
      context: { client: { clientName: "WEB", clientVersion: YOUTUBE_CLIENT_VERSION } },
      videoId,
    });
    return str(obj(obj(obj(answer)?.["microformat"])?.["playerMicroformatRenderer"])?.["category"]) === "Music";
  }

  // TIDAL

  /** What TIDAL finds for [text] among its "tracks", "albums" or "artists". */
  async tidalSearch(kind: "tracks" | "albums" | "artists", text: string): Promise<Obj[]> {
    const params = query({ query: text, limit: "10", countryCode: this.country.length === 2 ? this.country : "US" });
    const answer = await this.getJson(`${TIDAL_API}/search/${kind}?${params}`, { "x-tidal-token": TIDAL_TOKEN });
    return objects(obj(answer)?.["items"]);
  }

  // SoundCloud

  private soundCloudId(fresh: boolean): Promise<string> {
    if (!fresh && this.clientId !== undefined) return this.clientId;
    const reading = this.readSoundCloudId();
    this.clientId = reading;
    // A failed reading isn't kept, so the next lookup tries again.
    reading.catch(() => {
      if (this.clientId === reading) this.clientId = undefined;
    });
    return reading;
  }

  /** The key SoundCloud's website asks its API with, in one of the last scripts the page loads. */
  private async readSoundCloudId(): Promise<string> {
    const page = await this.fetcher(SOUNDCLOUD_URL);
    const scripts = [...page.text.matchAll(soundCloudScriptRegex)].map((match) => match[1] ?? "").reverse();
    for (const script of scripts) {
      const found = soundCloudIdRegex.exec((await this.fetcher(script)).text)?.[1];
      if (found !== undefined) return found;
    }
    throw new Unreachable("SoundCloud's key wasn't found");
  }

  private async soundCloud(path: string, params: Record<string, string>): Promise<unknown> {
    const ask = async (fresh: boolean) => this.getJson(`${SOUNDCLOUD_API}/${path}?${query({ ...params, client_id: await this.soundCloudId(fresh) })}`);
    try {
      return await ask(false);
    } catch (refused) {
      // An old key is turned away; the website's code has the new one.
      if (!(refused instanceof ServiceRefused) || (refused.code !== 401 && refused.code !== 403)) throw refused;
      return ask(true);
    }
  }

  /** What SoundCloud finds for [text] among its "tracks", "albums" or "users". */
  async soundCloudSearch(kind: "tracks" | "albums" | "users", text: string): Promise<Obj[]> {
    return objects(obj(await this.soundCloud(`search/${kind}`, { q: text, limit: "10" }))?.["collection"]);
  }

  /** What the SoundCloud link [url] is, a track, a set or an account, as SoundCloud's API answers. */
  async soundCloudItem(url: string): Promise<Obj | undefined> {
    return obj(await this.soundCloud("resolve", { url }));
  }

  // Spotify, whose own search needs an account

  /**
   * The Spotify songs ListenBrainz knows by [title] and [artist], the likeliest first: a song's
   * releases, remasters and compilations, each its own song on Spotify.
   */
  async spotifyIds(title: string, artist: string): Promise<string[]> {
    const answer = await this.postJson(LISTENBRAINZ_SPOTIFY_IDS, [{ artist_name: artist, release_name: "", track_name: title }]);
    if (!Array.isArray(answer)) throw new Unreachable("Not a list");
    return list(obj(answer[0])?.["spotify_track_ids"]).map(str).filter((id) => id.trim() !== "");
  }

  /** A Spotify song's name, artist and length, from its own page, which anyone can read; null when it's gone. */
  async spotifySong(id: string): Promise<{ metadata: Metadata; url: string } | null> {
    const url = `https://open.spotify.com/track/${id}`;
    const reply = await this.fetcher(url);
    if (!reply.ok) return null;
    const metadata = spotify(reply.text, "track");
    return metadata === null ? null : { metadata, url };
  }
}

/** A SoundCloud upload, by the artist it credits, or else by whoever uploaded it. */
export function soundCloudTrack(item: Obj): Metadata | null {
  const title = str(item["title"]);
  if (title.trim() === "") return null;
  const credited = str(obj(item["publisher_metadata"])?.["artist"]);
  return { title, artist: credited.trim() !== "" ? credited : str(obj(item["user"])?.["username"]), type: "track" };
}

/** A TIDAL song, with its version, e.g. "Remix", as TIDAL shows it. */
export function tidalTrack(item: Obj): Metadata | null {
  const title = str(item["title"]);
  if (title.trim() === "") return null;
  const version = str(item["version"]);
  const names = objects(item["artists"]).map((artist) => str(artist["name"])).filter((name) => name.trim() !== "");
  const artist = names.length > 0 ? names.join(", ") : str(obj(item["artist"])?.["name"]);
  return { title: version.trim() !== "" && version !== "null" ? `${title} (${version})` : title, artist, type: "track" };
}

/** The text of one column of a YouTube Music search row. */
export function columnText(column: Obj): string {
  return runsText(obj(column["musicResponsiveListItemFlexColumnRenderer"])?.["text"]);
}
