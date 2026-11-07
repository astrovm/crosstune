import assert from "node:assert/strict";
import { test } from "node:test";
import { currentSong, songFrom, type TabSong } from "../src/song.ts";

const named = (host: string, title: string, artist: string, album = "") => songFrom(host, { title, artist, album });

test("a music site's own names stay as they are", () => {
  assert.deepEqual(named("open.spotify.com", "Song - Remastered 2011", "Band"), { title: "Song - Remastered 2011", artist: "Band" });
  assert.deepEqual(named("www.deezer.com", "Top Of The World", "Carpenters", "Greatest Singles"), { title: "Top Of The World", artist: "Carpenters" });
});

test("YouTube Music's song with an album stays whole; a video without one is read from its title", () => {
  assert.deepEqual(named("music.youtube.com", "Song - Part 2", "Band", "An Album"), { title: "Song - Part 2", artist: "Band" });
  assert.deepEqual(named("music.youtube.com", "Africa", "Toto"), { title: "Africa", artist: "Toto" });
  assert.deepEqual(named("music.youtube.com", "Queen - Bohemian Rhapsody (Lyrics)", "7clouds"), { title: "Bohemian Rhapsody", artist: "Queen" });
});

test("a video on YouTube is a song when it says it's music", () => {
  assert.deepEqual(named("www.youtube.com", "Toto - Africa (Official HD Video)", "TotoVEVO"), { title: "Africa", artist: "Toto" });
  assert.deepEqual(named("www.youtube.com", "Africa", "Toto - Topic"), { title: "Africa", artist: "Toto" });
  assert.deepEqual(named("m.youtube.com", "YOASOBI「アイドル」 Official Music Video", "Ayase / YOASOBI"), { title: "アイドル", artist: "YOASOBI" });
});

test("any other video is no song, so its name goes nowhere", () => {
  assert.equal(named("www.youtube.com", "A New Approach To An Ancient Linux Terminal Problem", "Brodie Robertson"), null);
  assert.equal(named("www.youtube.com", "My cat sleeping", "Someone"), null);
});

test("SoundCloud's uploads often name the artist in the title", () => {
  assert.deepEqual(named("soundcloud.com", "Toto - Africa", "some uploader"), { title: "Africa", artist: "Toto" });
});

test("no title or no artist, as an ad can be, is no song", () => {
  assert.equal(named("open.spotify.com", "", "Band"), null);
  assert.equal(named("open.spotify.com", "Advertisement", ""), null);
  assert.equal(named("open.spotify.com", "  ", "  "), null);
});

test("a compilation's many artists don't name one: the title may", () => {
  assert.deepEqual(named("open.spotify.com", "Band - Song", "Various Artists"), { title: "Song", artist: "Band" });
  assert.equal(named("open.spotify.com", "Song", "Various Artists"), null);
});

const song = (title: string, tabId: number, at: number, playing: boolean): TabSong => ({ title, artist: "Band", tabId, at, playing });

test("the current song is the one playing, whichever tab said so last", () => {
  assert.equal(currentSong([song("Paused later", 1, 200, false), song("Playing", 2, 100, true)])?.title, "Playing");
  assert.equal(currentSong([song("Older", 1, 100, true), song("Newer", 2, 200, true)])?.title, "Newer");
});

test("with nothing playing, the last one heard stays", () => {
  assert.equal(currentSong([song("Older", 1, 100, false), song("Newer", 2, 200, false)])?.title, "Newer");
  assert.equal(currentSong([]), null);
});
