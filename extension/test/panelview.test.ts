import assert from "node:assert/strict";
import { test } from "node:test";
import { activeLine, fitsLength, follows, viewOf, type ViewInput } from "../src/panelview.ts";
import type { Lyrics } from "../src/lyrics.ts";

const words: Lyrics = { kind: "found", words: "One\nTwo", lines: [], source: "letras" };
const timed: Lyrics = { kind: "found", words: "One\nTwo", lines: [{ timeMs: 1_000, text: "One" }, { timeMs: 5_000, text: "Two" }], lengthMs: 200_000, source: "lrclib" };
const song = { title: "Song", artist: "Band", tabId: 1, at: 1, playing: true };
const base: ViewInput = { access: true, online: true, song, lyrics: words };

test("every state shows one thing, the most pressing", () => {
  assert.equal(viewOf({ ...base, access: false }), "access");
  assert.equal(viewOf({ ...base, song: null, lyrics: null }), "noSong");
  assert.equal(viewOf({ ...base, song: null, lyrics: null, online: false }), "noSong");
  assert.equal(viewOf(base), "words");
  assert.equal(viewOf({ ...base, lyrics: null }), "loading");
  assert.equal(viewOf({ ...base, lyrics: { kind: "none" } }), "none");
  assert.equal(viewOf({ ...base, lyrics: { kind: "instrumental" } }), "instrumental");
  assert.equal(viewOf({ ...base, lyrics: { kind: "unavailable" } }), "unavailable");
});

test("offline, words already found stay; anything else says it's offline", () => {
  assert.equal(viewOf({ ...base, online: false }), "words");
  assert.equal(viewOf({ ...base, online: false, lyrics: null }), "offline");
  assert.equal(viewOf({ ...base, online: false, lyrics: { kind: "unavailable" } }), "offline");
});

test("the line lit is the one being sung, a moment early", () => {
  const lines = [{ timeMs: 1_000, text: "One" }, { timeMs: 5_000, text: "Two" }, { timeMs: 9_000, text: "" }];
  assert.equal(activeLine(lines, 0), -1);
  assert.equal(activeLine(lines, 800), 0);
  assert.equal(activeLine(lines, 4_000), 0);
  assert.equal(activeLine(lines, 5_000), 1);
  assert.equal(activeLine(lines, 100_000), 2);
  assert.equal(activeLine([], 1_000), -1);
});

test("lines follow the music only when the site says where it is and they're for this recording", () => {
  assert.equal(follows(timed, { positionMs: 1, durationMs: 201_000, playing: true }), true);
  assert.equal(follows(timed, { positionMs: 1, durationMs: 300_000, playing: true }), false);
  assert.equal(follows(timed, { positionMs: 1, playing: true }), true);
  assert.equal(follows(timed, null), false);
  assert.equal(follows(words, { positionMs: 1, durationMs: 1, playing: true }), false);
  assert.equal(follows(null, { positionMs: 1, playing: true }), false);
});

test("lengths within three seconds are one recording, or there's no telling they aren't", () => {
  assert.equal(fitsLength(100_000, 103_000), true);
  assert.equal(fitsLength(100_000, 103_001), false);
  assert.equal(fitsLength(undefined, 103_001), true);
  assert.equal(fitsLength(100_000, undefined), true);
});
