// Runs the extension's own lyrics finder in node against the live sites and prints what it found.
// Read-only: it asks the same public endpoints the panel does.
//   node scripts/try-lyrics.mjs <title> <artist> [lengthMs]

import { realFetcher } from "../src/http.ts";
import { findLyrics } from "../src/lyrics.ts";

const [title, artist, length] = process.argv.slice(2);
if (title === undefined || artist === undefined) {
  console.error("usage: node scripts/try-lyrics.mjs <title> <artist> [lengthMs]");
  process.exit(2);
}
const started = Date.now();
const lyrics = await findLyrics({ title, artist, ...(length === undefined ? {} : { lengthMs: Number(length) }) }, realFetcher(5000));
const took = `${Date.now() - started} ms`;
if (lyrics.kind !== "found") console.log(`${lyrics.kind}  (${took})`);
else console.log(`${lyrics.source}, ${lyrics.lines.length > 0 ? `${lyrics.lines.length} timed lines` : "untimed"}, ${lyrics.words.split("\n").length} lines  (${took})`);
