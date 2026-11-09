// The sites the extension watches for music links, and the ones it asks for what it needs to
// know. One list, so the manifest and the code can't drift apart; the build writes it into the manifest.

/** Pages whose navigation is looked at. */
export const WATCHED_HOSTS = [
  "open.spotify.com", "spotify.link",
  "music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be",
  "music.apple.com", "geo.music.apple.com", "itunes.apple.com",
  "www.deezer.com", "deezer.com", "link.deezer.com", "deezer.page.link", "dzr.page.link",
  "tidal.com", "www.tidal.com", "listen.tidal.com",
  "soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com",
] as const;

/** Services' own endpoints that are asked and aren't pages a link opens on. */
export const API_HOSTS = [
  "api.deezer.com", "api.tidal.com", "api-v2.soundcloud.com", "a-v2.sndcdn.com", "labs.api.listenbrainz.org",
] as const;

/**
 * Lyrics sites a browser may only read with leave. LRCLIB and Letras's search answer any web page
 * (they send "access-control-allow-origin: *"), so they need none, and aren't asked for it.
 */
export const LYRICS_HOSTS = ["music.163.com", "www.letras.com", "genius.com"] as const;

/** Spotify's short links come from subdomains such as "abc.spotify.link". */
export const WILDCARD_HOSTS = ["*.spotify.link"] as const;

export const HOST_PERMISSIONS: string[] = [...WATCHED_HOSTS, ...API_HOSTS, ...LYRICS_HOSTS, ...WILDCARD_HOSTS].map((host) => `https://${host}/*`);

/**
 * Sites whose pages play music, which the content script reads what's playing from. All are among
 * [WATCHED_HOSTS], so reading them asks for no more than opening their links does.
 */
export const PLAYER_HOSTS = [
  "music.youtube.com", "www.youtube.com", "m.youtube.com",
  "open.spotify.com",
  "music.apple.com",
  "www.deezer.com",
  "tidal.com", "listen.tidal.com",
  "soundcloud.com", "m.soundcloud.com",
] as const;

export const PLAYER_MATCHES: string[] = PLAYER_HOSTS.map((host) => `https://${host}/*`);
