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

/** Spotify's short links come from subdomains such as "abc.spotify.link". */
export const WILDCARD_HOSTS = ["*.spotify.link"] as const;

export const HOST_PERMISSIONS: string[] = [...WATCHED_HOSTS, ...API_HOSTS, ...WILDCARD_HOSTS].map((host) => `https://${host}/*`);
