import assert from "node:assert/strict";
import { test } from "node:test";
import { appleMusic, deezer, soundCloud, spotify, tidal, youtube } from "../src/parsers.ts";
import { fixture } from "./support.ts";

test("a Spotify page names the song, its artist and its length", () => {
  assert.deepEqual(spotify(fixture("spotify-track.html"), "track"), { title: "Never Gonna Give You Up", artist: "Rick Astley", type: "track", lengthMs: 214_000 });
});

test("Spotify's album title no longer starts with 'Listen to'", () => {
  assert.deepEqual(spotify(fixture("spotify-album.html"), "album"), { title: "Discovery", artist: "Daft Punk", type: "album" });
});

test("a Spotify artist page is the artist's name", () => {
  assert.equal(spotify(fixture("spotify-artist.html"), "artist")?.title, "Rick Astley");
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
