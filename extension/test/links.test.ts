import assert from "node:assert/strict";
import { test } from "node:test";
import { isShortLink, linkFor, linkFromPage, youtubePlaylistOn, youtubeVideoOn } from "../src/links.ts";

const SPOTIFY = "4cOdK2wGLETKBW3PvgPWqT";

function shape(url: string) {
  const link = linkFor(url);
  return link === null ? null : [link.service, link.type, link.id];
}

test("Spotify links, with locale prefixes, tracking and URIs", () => {
  assert.deepEqual(shape(`https://open.spotify.com/track/${SPOTIFY}?si=abc`), ["spotify", "track", SPOTIFY]);
  assert.deepEqual(shape(`https://open.spotify.com/intl-es/album/${SPOTIFY}`), ["spotify", "album", SPOTIFY]);
  assert.deepEqual(shape(`https://open.spotify.com/artist/${SPOTIFY}`), ["spotify", "artist", SPOTIFY]);
  assert.deepEqual(shape(`https://open.spotify.com/playlist/${SPOTIFY}`), ["spotify", "playlist", SPOTIFY]);
  assert.deepEqual(shape(`https://open.spotify.com/?uri=spotify:track:${SPOTIFY}`), ["spotify", "track", SPOTIFY]);
  assert.equal(shape("https://open.spotify.com/"), null);
  assert.equal(shape("https://open.spotify.com/track/short"), null);
});

test("YouTube and YouTube Music links", () => {
  assert.deepEqual(shape("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"), ["youtube", "track", "dQw4w9WgXcQ"]);
  assert.deepEqual(shape("https://youtu.be/dQw4w9WgXcQ"), ["youtube", "track", "dQw4w9WgXcQ"]);
  assert.deepEqual(shape("https://www.youtube.com/shorts/dQw4w9WgXcQ"), ["youtube", "track", "dQw4w9WgXcQ"]);
  assert.deepEqual(shape("https://music.youtube.com/watch?v=dQw4w9WgXcQ"), ["youtubemusic", "track", "dQw4w9WgXcQ"]);
  assert.deepEqual(shape("https://music.youtube.com/browse/MPREb_7ltM34kr0mH"), ["youtubemusic", "album", "MPREb_7ltM34kr0mH"]);
  assert.deepEqual(shape("https://music.youtube.com/channel/UCRr1xG_2WIDs18a6cIiCxeA"), ["youtubemusic", "artist", "UCRr1xG_2WIDs18a6cIiCxeA"]);
  assert.deepEqual(shape("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijkl"), ["youtubemusic", "playlist", "OLAK5uy_abcdefghijkl"]);
  assert.equal(shape("https://www.youtube.com/"), null);
  assert.equal(shape("https://www.youtube.com/@someone"), null);
});

test("Apple Music links, where an album link with ?i= is a song", () => {
  assert.deepEqual(shape("https://music.apple.com/us/album/discovery/697194953"), ["applemusic", "album", "697194953"]);
  assert.deepEqual(shape("https://music.apple.com/us/album/one-more-time/697194953?i=697195787"), ["applemusic", "track", "697195787"]);
  assert.deepEqual(shape("https://music.apple.com/ar/song/one-more-time/697195787"), ["applemusic", "track", "697195787"]);
  assert.deepEqual(shape("https://music.apple.com/us/artist/daft-punk/5468295"), ["applemusic", "artist", "5468295"]);
  assert.equal(linkFor("https://music.apple.com/ar/album/discovery/697194953")?.region, "ar");
  assert.equal(shape("https://music.apple.com/us/browse"), null);
});

test("Deezer, TIDAL and SoundCloud links", () => {
  assert.deepEqual(shape("https://www.deezer.com/en/track/3135556"), ["deezer", "track", "3135556"]);
  assert.deepEqual(shape("https://www.deezer.com/album/302127"), ["deezer", "album", "302127"]);
  assert.deepEqual(shape("https://www.deezer.com/artist/27"), ["deezer", "artist", "27"]);
  assert.deepEqual(shape("https://tidal.com/browse/track/77646164"), ["tidal", "track", "77646164"]);
  assert.deepEqual(shape("https://tidal.com/browse/album/77646163/track/77646164"), ["tidal", "track", "77646164"]);
  assert.deepEqual(shape("https://listen.tidal.com/artist/1798"), ["tidal", "artist", "1798"]);
  assert.deepEqual(shape("https://soundcloud.com/flume"), ["soundcloud", "artist", "flume"]);
  assert.deepEqual(shape("https://soundcloud.com/flume/tracks"), ["soundcloud", "artist", "flume"]);
  assert.deepEqual(shape("https://soundcloud.com/flume/never-be-like-you"), ["soundcloud", "track", "flume/never-be-like-you"]);
  assert.deepEqual(shape("https://soundcloud.com/flume/sets/some-set"), ["soundcloud", "playlist", "flume/sets/some-set"]);
  assert.equal(shape("https://soundcloud.com/discover"), null);
  assert.equal(shape("https://notdeezer.com/track/1"), null);
});

test("Other sites and other schemes are not music links", () => {
  assert.equal(shape("https://example.com/track/3135556"), null);
  assert.equal(shape("ftp://open.spotify.com/track/" + SPOTIFY), null);
  assert.equal(shape("not a url"), null);
});

test("short links are told apart, and found in the page they land on", () => {
  assert.ok(isShortLink(new URL("https://spotify.link/abc")));
  assert.ok(isShortLink(new URL("https://sender.spotify.link/abc")));
  assert.ok(isShortLink(new URL("https://on.soundcloud.com/abc")));
  assert.ok(isShortLink(new URL("https://link.deezer.com/s/abc")));
  assert.ok(!isShortLink(new URL("https://open.spotify.com/track/" + SPOTIFY)));
  assert.deepEqual(linkFromPage(`<a href="https://open.spotify.com/intl-fr/album/${SPOTIFY}">`)?.id, SPOTIFY);
  assert.equal(linkFromPage("<html></html>"), null);
});

test("YouTube and YouTube Music share videos and playlists, nothing else", () => {
  const video = linkFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
  assert.ok(video);
  assert.equal(youtubeVideoOn(video, "youtubemusic"), "https://music.youtube.com/watch?v=dQw4w9WgXcQ");
  assert.equal(youtubeVideoOn(video, "spotify"), null);
  const list = linkFor("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijkl");
  assert.ok(list);
  assert.equal(youtubePlaylistOn(list, "youtube"), "https://www.youtube.com/playlist?list=OLAK5uy_abcdefghijkl");
  assert.equal(youtubePlaylistOn(list, "deezer"), null);
});
