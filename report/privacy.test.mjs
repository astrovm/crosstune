// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker from "./worker.js";

/**
 * How README's Privacy section and the English privacy page name each column a report fills. A new column needs
 * its words here, and then in both, so the privacy text never leaves out what a report carries.
 */
const SAID = {
  // Each is the words, or a list of ways either text may say it.
  version: "Crosstune and Android versions",
  android: "Crosstune and Android versions",
  title: "title",
  artist: "artist",
  app: "app Crosstune follows",
  timed: "how they're timed",
  synced: "how they're timed",
  sung: "how they're timed",
  length_ms: "length",
  source: "where its lyrics came from",
  timing: "words and times of each line",
  comment: "what you write",
  problem: ["problems Crosstune finds", "when it finds a song with no lyrics"],
  app_package: "package name",
  speed: "playback speed",
  lead_ms: "how far ahead Crosstune shows the words",
  early_ms: "how far ahead Crosstune shows the words",
  timing_log: "timing notes",
  recording: "recording",
  lyric_line: "whole line of lyrics",
  word: "the word",
  reading_shown: "the reading shown",
  reader: "what made it",
  language: "its script",
  right_reading: "the right reading",
};

/** The columns the Worker's insert names, as it sends them to D1. */
async function columns() {
  let sql = "";
  const env = {
    DB: { prepare: (text) => ((sql ||= text), { bind: () => ({ run: async () => ({ meta: { changes: 1 } }), first: async () => ({ allowed: 1 }) }) }) },
    LIMIT: { limit: async () => ({ success: true }) },
    NETWORK_KEY: "made-up-key",
  };
  const request = new Request("https://report.crosstune.cc/reports", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8", "CF-Connecting-IP": "203.0.113.7" },
    body: JSON.stringify({ version: "2.25.0", song: { title: "Demo", artist: "Band" } }),
  });
  await worker.fetch(request, env);
  return sql.match(/INSERT INTO reports \(([^)]+)\)/)[1].split(",").map((it) => it.trim());
}

/** Whether [text] says [words], or one of them. */
const says = (text, words) => [words].flat().some((it) => text.includes(it));

const repo = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("every column a report fills has its words in the privacy text", async () => {
  assert.deepEqual((await columns()).sort(), Object.keys(SAID).sort());
});

test("README's Privacy says what each report carries", () => {
  const bullets = repo("README.md").split("\n").filter((line) => line.startsWith("- **Report a problem** in the lyrics") || line.startsWith("- Flagging a word's reading")).join("\n");
  assert.ok(bullets.length > 0);
  for (const [column, words] of Object.entries(SAID)) assert.ok(says(bullets, words), `README doesn't say ${column}: "${words}"`);
});

test("the English privacy page says what each report carries", () => {
  const body = JSON.parse(repo("site/strings/en.json"))["privacy_body"].map(([, text]) => text);
  const paragraphs = body.filter((text) => text.startsWith("Report a problem, in the lyrics") || text.startsWith("Marking a word's reading wrong")).join("\n");
  assert.ok(paragraphs.length > 0);
  for (const [column, words] of Object.entries(SAID)) assert.ok(says(paragraphs, words), `privacy_body doesn't say ${column}: "${words}"`);
});
