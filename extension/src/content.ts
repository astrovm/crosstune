// Runs on music sites' pages and reads what they say is playing: the Media Session they fill in for
// the browser's own media controls, and the position of their <video> or <audio>, for the sites that
// keep one in the page. It reads and tells the extension; it never changes the page, and sends nothing out.

import { POSITION_MESSAGE, SONG_MESSAGE, songFrom, type Position, type SongMessage } from "./song.ts";

const CHECK_MS = 1_000;
/** A new song waits a check more, for the player to hold its length rather than the last song's. */
const SETTLED_CHECKS = 2;

function isAdShowing(): boolean {
  // YouTube's two sites name an ad in the Media Session as they would a song, and mark the player.
  return document.querySelector(".ad-showing") !== null;
}

/** The media element making the sound: the one going, the longest if there are several; else one that's loaded. */
function mediaElement(): HTMLMediaElement | null {
  const all = [...document.querySelectorAll<HTMLMediaElement>("video, audio")].filter((media) => Number.isFinite(media.duration) && media.duration > 0);
  const going = all.filter((media) => !media.paused && !media.ended);
  return [...(going.length > 0 ? going : all)].sort((a, b) => b.duration - a.duration)[0] ?? null;
}

function isPlaying(media: HTMLMediaElement | null): boolean {
  if (media !== null) return !media.paused && !media.ended;
  return navigator.mediaSession.playbackState === "playing";
}

function videoId(): string | undefined {
  if (!location.hostname.endsWith("youtube.com")) return undefined;
  return new URLSearchParams(location.search).get("v") ?? undefined;
}

function report(): SongMessage["song"] {
  const metadata = navigator.mediaSession.metadata;
  if (metadata === null) return null;
  const named = songFrom(location.hostname, { title: metadata.title, artist: metadata.artist, album: metadata.album });
  if (named === null) return null;
  const media = mediaElement();
  const id = videoId();
  return {
    ...named,
    playing: isPlaying(media),
    ...(media === null ? {} : { lengthMs: Math.round(media.duration * 1_000) }),
    ...(id === undefined ? {} : { videoId: id }),
  };
}

let told = "";
let seen = "";
let seenFor = 0;
let timer: ReturnType<typeof setInterval> | undefined;

function check(): void {
  if (isAdShowing()) return;
  const song = report();
  const text = JSON.stringify(song);
  const name = JSON.stringify([song?.title, song?.artist]);
  seenFor = name === seen ? seenFor + 1 : 1;
  seen = name;
  if (text === told || seenFor < SETTLED_CHECKS) return;
  told = text;
  const message: SongMessage = { type: SONG_MESSAGE, song };
  try {
    void chrome.runtime.sendMessage(message).catch(() => {});
  } catch {
    // The extension was updated or removed: a page's old copy of this script has nothing left to tell.
    clearInterval(timer);
  }
}

function position(): Position | null {
  if (isAdShowing()) return null;
  const media = mediaElement();
  if (media === null) return null;
  return { positionMs: Math.round(media.currentTime * 1_000), durationMs: Math.round(media.duration * 1_000), playing: isPlaying(media) };
}

chrome.runtime.onMessage.addListener((message: { type?: string } | undefined, _sender, respond) => {
  if (message?.type === POSITION_MESSAGE) respond(position());
});

timer = setInterval(check, CHECK_MS);
check();
