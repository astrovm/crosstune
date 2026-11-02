import assert from "node:assert/strict";
import { test } from "node:test";
import { appleMusic, deezer, soundCloud, spotify, tidal, youtube } from "../src/parsers.ts";
import { fixture } from "./support.ts";

test("a Spotify embed page names the song, its artist and its length", () => {
  assert.deepEqual(spotify(fixture("spotify-embed-track.html"), "track"), { title: "Never Gonna Give You Up", artist: "Rick Astley", type: "track", lengthMs: 213_573 });
});

test("a Spotify album's embed page names its artist under its name", () => {
  assert.deepEqual(spotify(fixture("spotify-embed-album.html"), "album"), { title: "Discovery", artist: "Daft Punk", type: "album" });
});

test("a Spotify artist's or playlist's embed page is only its name", () => {
  assert.deepEqual(spotify(fixture("spotify-embed-artist.html"), "artist"), { title: "Rick Astley", artist: "", type: "artist" });
  assert.deepEqual(spotify(fixture("spotify-embed-playlist.html"), "playlist"), { title: "Today’s Top Hits", artist: "", type: "playlist" });
});

test("a song by several artists names them all, and a broken embed page is nothing", () => {
  const data = (entity: object) => `<script id="__NEXT_DATA__">${JSON.stringify({ props: { pageProps: { state: { data: { entity } } } } })}</script>`;
  assert.deepEqual(spotify(data({ name: "Song", artists: [{ name: "A" }, { name: " " }, { name: "B" }] }), "track"), { title: "Song", artist: "A, B", type: "track" });
  assert.equal(spotify(`<script id="__NEXT_DATA__">{not json</script>`, "track"), null);
  assert.equal(spotify(data({ name: " " }), "track"), null);
});

test("a page with nothing to read is no metadata", () => {
  assert.equal(spotify("<html></html>", "track"), null);
  assert.equal(tidal("<html></html>", "track"), null);
});

test("YouTube's title is cleaned of the video's decoration, and the channel is the artist", () => {
  const named = youtube(JSON.parse(fixture("youtube-oembed.json")));
  assert.equal(named?.title, "Never Gonna Give You Up");
  assert.equal(named?.artist, "Rick Astley");
});

test("Apple Music, Deezer, TIDAL and SoundCloud answers name the item", () => {
  assert.equal(appleMusic(JSON.parse(fixture("itunes-lookup-song.json")), "track")?.title, "Never Gonna Give You Up");
  assert.deepEqual(deezer(JSON.parse(fixture("deezer-track.json")), "track"), { title: "Harder, Better, Faster, Stronger", artist: "Daft Punk", type: "track", lengthMs: 226_000 });
  assert.deepEqual(tidal(fixture("tidal-track.html"), "track"), { title: "Pleasant Relief", artist: "Afterhour Chillout", type: "track" });
  assert.deepEqual(soundCloud(JSON.parse(fixture("soundcloud-oembed.json")), "track"), { title: "Never Be Like You", artist: "Flume", type: "track" });
});
