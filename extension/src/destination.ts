// Where a music link should go for someone who prefers [preferred]: nowhere (leave it alone) or
// to the same item there, or to that service's search when it isn't found. This is the app's
// flow for one link (LinkResolver, ExactMatcher, Destinations) without the parts that only the
// app needs. Whatever can't be told leaves the link alone, so the user is never stuck.

import { Apis } from "./apis.ts";
import type { Fetcher } from "./http.ts";
import { Unreachable } from "./http.ts";
import { fromUrl, isShortLink, linkFromPage, youtubePlaylistOn, youtubeVideoOn, type MusicLink } from "./links.ts";
import { canMatchExactly, lookFor } from "./matcher.ts";
import { resolveMetadata } from "./resolver.ts";
import { searchQuery, searchUrl, type Service } from "./services.ts";

/** How the destination was found, for the one who wants to know: the same item, the same YouTube video, or a search. */
export type How = "exact" | "direct" | "search";

export type Decision = { kind: "leave" } | { kind: "go"; url: string; how: How };

export interface Io {
  fetch: Fetcher;
  /** The two-letter country the user is in, or blank when unknown. */
  country: string;
}

const LEAVE: Decision = { kind: "leave" };

function go(url: string, how: How): Decision {
  return { kind: "go", url, how };
}

/** Where a short link leads, which has to be followed to see. */
async function followShortLink(url: URL, io: Io): Promise<MusicLink | null> {
  const reply = await io.fetch(url.href);
  try {
    return fromUrl(new URL(reply.url)) ?? linkFromPage(reply.text);
  } catch {
    return linkFromPage(reply.text);
  }
}

export async function decide(rawUrl: string, preferred: Service, io: Io): Promise<Decision> {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    return LEAVE;
  }
  const apis = new Apis(io.fetch, io.country);
  try {
    const link = isShortLink(url) ? await followShortLink(url, io) : fromUrl(url);
    if (link === null || link.service === preferred) return LEAVE;
    return await decideFor(link, preferred, io, apis);
  } catch (error) {
    // Nothing answered: the original link is what the user asked for.
    if (error instanceof Unreachable) return LEAVE;
    throw error;
  }
}

async function decideFor(link: MusicLink, preferred: Service, io: Io, apis: Apis): Promise<Decision> {
  const playlist = youtubePlaylistOn(link, preferred);
  if (playlist !== null) return go(playlist, "direct");
  // A playlist is someone's own, and is on no other service.
  if (link.type === "playlist") return LEAVE;
  if (link.service === "youtube" && link.type === "track") {
    // A video on YouTube is anything, and only a song is the music this is about.
    if ((await apis.isMusicVideo(link.id)) !== true) return LEAVE;
  }
  const video = youtubeVideoOn(link, preferred);
  if (video !== null) return go(video, "direct");

  const metadata = await resolveMetadata(link, io.fetch, apis);
  if (metadata === null) return LEAVE;
  if (canMatchExactly(preferred, metadata.type)) {
    const match = await lookFor(preferred, metadata, apis);
    if (match.kind === "found") return go(match.url, "exact");
    if (match.kind === "failed") return LEAVE;
  }
  // Much of SoundCloud is on no other service, so a search there would only take people away from it.
  if (link.service === "soundcloud") return LEAVE;
  return go(searchUrl(preferred, searchQuery(metadata)), "search");
}
