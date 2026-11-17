import assert from "node:assert/strict";
import { test } from "node:test";
import { SecondsClock, readingOf } from "../src/timeline.ts";

test("a progress bar says where the song is and how long it lasts, in seconds", () => {
  assert.deepEqual(readingOf("9", "249"), { seconds: 9, lengthSeconds: 249 });
  assert.deepEqual(readingOf("0", "249"), { seconds: 0, lengthSeconds: 249 });
});

test("a bar that's empty or says nothing sensible says nothing", () => {
  assert.equal(readingOf(null, "249"), null);
  assert.equal(readingOf("9", null), null);
  assert.equal(readingOf("", "249"), null);
  assert.equal(readingOf("9", " "), null);
  assert.equal(readingOf("soon", "249"), null);
  assert.equal(readingOf("9", "long"), null);
  assert.equal(readingOf("-1", "249"), null);
  assert.equal(readingOf("0", "0"), null);
});

test("the part of a second since the bar last moved is added to its whole seconds", () => {
  const clock = new SecondsClock();
  assert.equal(clock.positionMs(9, true, 1_000), 9_000);
  assert.equal(clock.positionMs(9, true, 1_400), 9_400);
  assert.equal(clock.positionMs(10, true, 2_000), 10_000);
  assert.equal(clock.positionMs(10, true, 2_250), 10_250);
});

test("a bar that stalls doesn't run on past its second", () => {
  const clock = new SecondsClock();
  clock.positionMs(9, true, 1_000);
  assert.equal(clock.positionMs(9, true, 6_000), 9_999);
});

test("a time that went backwards adds nothing", () => {
  const clock = new SecondsClock();
  clock.positionMs(9, true, 1_000);
  assert.equal(clock.positionMs(9, true, 900), 9_000);
});

test("paused, the position stays on its second, and starts over once it goes again", () => {
  const clock = new SecondsClock();
  clock.positionMs(9, true, 1_000);
  assert.equal(clock.positionMs(9, false, 1_500), 9_000);
  assert.equal(clock.positionMs(9, false, 9_000), 9_000);
  assert.equal(clock.positionMs(9, true, 9_100), 9_100);
});

test("a seek lands on the new second", () => {
  const clock = new SecondsClock();
  clock.positionMs(9, true, 1_000);
  assert.equal(clock.positionMs(120, true, 1_300), 120_000);
  assert.equal(clock.positionMs(120, true, 1_800), 120_500);
});
