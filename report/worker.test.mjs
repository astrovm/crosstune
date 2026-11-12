// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import * as all from "./worker.js";
import worker, { kindOf, networkDayKey, readReport, sameKey } from "./worker.js";

/**
 * D1 and the rate limit, as a test needs them: the reports saved, every other statement asked, and whether the
 * limit lets one through. Every statement changes a row, so no count is ever reached.
 */
function env({ allowed = true } = {}) {
  const saved = [];
  const asked = [];
  const keys = [];
  return {
    saved,
    asked,
    keys,
    NETWORK_KEY: "made-up-key",
    DB: {
      prepare: (sql) => ({
        bind: (...values) => ({
          run: async () => ((sql.startsWith("INSERT INTO reports ") ? saved : asked).push({ sql, values }), { meta: { changes: 1 } }),
          first: async () => (asked.push({ sql, values }), { why: null }),
        }),
      }),
    },
    LIMIT: { limit: async ({ key }) => (keys.push(key), { success: allowed }) },
  };
}

/** A request, with what Cloudflare tells of its sender in [cf]: its carrier is [cf.asn]. */
function post(body, { path = "/reports", method = "POST", headers = {}, cf = {} } = {}) {
  const request = new Request(`https://report.crosstune.cc${path}`, {
    method,
    // What every version of the app sends, through OkHttp.
    headers: { "CF-Connecting-IP": "203.0.113.7", "Content-Type": "application/json; charset=utf-8", ...headers },
    body: method === "GET" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
  return Object.defineProperty(request, "cf", { value: cf });
}

const song = {
  version: "2.12.0",
  android: "15 (35)",
  comment: "  The chorus comes late  ",
  song: {
    title: "Demo", artist: "Band", app: "YouTube Music", timed: true, synced: true, sung: false, lengthMs: 220937,
    source: "letras, timed by lrclib",
    timing: [{ line: "Take me I'm yours", timedMs: 34210, heardMs: 32900 }],
  },
};

test("a report is kept, with only what a report has", async () => {
  const e = env();
  const response = await worker.fetch(post({ ...song, ip: "198.51.100.1", extra: "dropped" }), e);
  assert.equal(response.status, 204);
  assert.equal(e.saved.length, 1);
  assert.deepEqual(e.saved[0].values, [
    "2.12.0", "15 (35)", "Demo", "Band", "YouTube Music", 1, 1, 0, 220937, "letras, timed by lrclib",
    JSON.stringify([{ line: "Take me I'm yours", timedMs: 34210, heardMs: 32900 }]), "The chorus comes late", null,
    // An app from before reports about timing sends none of what they add, nor a recording, and it's no wrong reading.
    null, null, null, null, null, null,
    null, null, null, null, null, null,
  ]);
  // The address only counts against the limit.
  assert.deepEqual(e.keys, ["203.0.113.7"]);
  assert.ok(!JSON.stringify(e.saved).includes("203.0.113.7"));
});

test("an insert binds one value for each column it names", async () => {
  const e = env();
  assert.equal((await worker.fetch(post(song), e)).status, 204);
  const named = e.saved[0].sql.match(/INSERT INTO reports \(([^)]+)\) SELECT ([?, ]+) WHERE /);
  assert.ok(named);
  const columns = named[1].split(",").map((part) => part.trim());
  const marks = named[2].split(",").map((part) => part.trim());
  assert.equal(columns.length, marks.length);
  assert.equal(marks.length, e.saved[0].values.length);
  assert.ok(marks.every((mark) => mark === "?"));
  assert.equal(columns[18], "recording");
  assert.deepEqual(columns.slice(19), ["lyric_line", "word", "reading_shown", "reader", "language", "right_reading"]);
});

test("a report about the app, with no song, is kept too", async () => {
  const e = env();
  assert.equal((await worker.fetch(post({ version: "2.12.0", comment: "Hi" }), e)).status, 204);
  assert.deepEqual(e.saved[0].values, ["2.12.0", null, null, null, null, null, null, null, null, null, null, "Hi", ...Array(13).fill(null)]);
});

test("anything else is turned away, and nothing kept", async () => {
  const e = env();
  assert.equal((await worker.fetch(post(song, { path: "/" }), e)).status, 404);
  assert.equal((await worker.fetch(post(song, { method: "GET" }), e)).status, 405);
  assert.equal((await worker.fetch(post("not json"), e)).status, 400);
  assert.equal((await worker.fetch(post([song]), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, version: " " }), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, song: "Demo" }), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, song: { ...song.song, artist: 7 } }), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, song: { ...song.song, timing: "late" } }), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, song: { ...song.song, timing: [{ line: "x", timedMs: -1, heardMs: 0 }] } }), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...song, song: { ...song.song, timing: [7] } }), e)).status, 400);
  assert.equal(e.saved.length, 0);
});

test("too much is turned away before it's read", async () => {
  const e = env();
  const big = { ...song, comment: "x".repeat(60 * 1024) };
  assert.equal((await worker.fetch(post(big), e)).status, 413);
  // Even when it says it's small.
  assert.equal((await worker.fetch(post(big, { headers: { "Content-Length": "10" } }), e)).status, 413);
  assert.equal(e.saved.length, 0);
});

test("a report passed on by the old address isn't counted again", async () => {
  const e = { ...env({ allowed: false }), FORWARD_KEY: "made-up-key" };
  assert.equal((await worker.fetch(post(song, { headers: { "X-Crosstune-Passed-On": "made-up-key" } }), e)).status, 204);
  assert.deepEqual(e.keys, []);
  // Another key, or none set here, and it's counted like any report.
  assert.equal((await worker.fetch(post(song, { headers: { "X-Crosstune-Passed-On": "another" } }), e)).status, 429);
  const none = env({ allowed: false });
  assert.equal((await worker.fetch(post(song, { headers: { "X-Crosstune-Passed-On": "" } }), none)).status, 429);
  assert.equal(e.saved.length, 1);
});

test("the old address's key is compared in constant time, by digests of the same length", async (t) => {
  assert.equal(await sameKey("made-up-key", "made-up-key"), true);
  for (const given of ["made-up-kez", "made-up-key ", "made", "", null, undefined]) assert.equal(await sameKey(given, "made-up-key"), false, String(given));
  assert.equal(await sameKey("made-up-key", undefined), false);
  assert.equal(await sameKey("", ""), false);
  // Both go through SHA-256 first, so how long the compare takes says nothing of how much of the key was right.
  const digest = t.mock.method(crypto.subtle, "digest");
  await sameKey("made-up-kez", "made-up-key");
  assert.equal(digest.mock.callCount(), 2);
  // And the Worker uses it.
  const e = { ...env({ allowed: false }), FORWARD_KEY: "made-up-key" };
  await worker.fetch(post(song, { headers: { "X-Crosstune-Passed-On": "made-up-key" } }), e);
  assert.ok(digest.mock.callCount() >= 4);
});

test("past the limit, nothing is kept", async () => {
  const e = env({ allowed: false });
  assert.equal((await worker.fetch(post(song), e)).status, 429);
  assert.equal(e.saved.length, 0);
});

test("texts are cut to their longest, and blank ones are none", () => {
  const report = readReport(JSON.stringify({ version: "2.12.0", comment: "é".repeat(3000), android: "" }));
  assert.equal(report.comment.length, 2000);
  assert.equal(report.android, null);
});

test("a song's details that aren't what they should be are left out", () => {
  const report = readReport(JSON.stringify({ version: "2.12.0", song: { title: "Demo", artist: "Band", timed: "yes", lengthMs: 1.5, timing: [] } }));
  assert.equal(report.timed, null);
  assert.equal(report.lengthMs, null);
  assert.equal(report.timing, null);
  // More lines put in time than a report keeps: the first 300 are kept, and the report with them. The app doesn't
  // cut them itself, and a report turned away for it would be lost.
  const many = Array.from({ length: 450 }, (_, i) => ({ line: `line ${i}`, timedMs: i, heardMs: i }));
  const kept = JSON.parse(readReport(JSON.stringify({ version: "2.12.0", song: { title: "Demo", artist: "Band", timing: many } })).timing);
  assert.equal(kept.length, 300);
  assert.deepEqual([kept[0].line, kept[299].line], ["line 0", "line 299"]);
  // Past the 300th, a line isn't looked at, so one that isn't a line put in time costs nothing.
  assert.equal(JSON.parse(readReport(JSON.stringify({ version: "2.12.0", song: { title: "Demo", artist: "Band", timing: [...many, 7] } })).timing).length, 300);
  assert.equal(readReport(JSON.stringify({ version: "2.12.0", song: { title: "Demo", artist: "Band", timing: [7, ...many] } })), null);
});

test("reports older than the policy allows are deleted", async () => {
  const keeping = env();
  await worker.scheduled({}, keeping);
  assert.deepEqual(keeping.asked[0], { sql: "DELETE FROM reports WHERE at < datetime('now', ?)", values: ["-180 days"] });
});

test("every export is one the Workers runtime can start with", () => {
  // It takes each export for an entrypoint, and won't start at all on a constant.
  for (const [name, value] of Object.entries(all)) {
    assert.ok(typeof value === "function" || (name === "default" && typeof value.fetch === "function"), name);
  }
});

test("a problem the app found by itself says which, and one it doesn't know is none", async () => {
  const e = env();
  await worker.fetch(post({ version: "2.12.0", problem: "no_lyrics", song: { title: "Demo", artist: "Band" } }), e);
  await worker.fetch(post({ version: "2.12.0", problem: "<script>", song: { title: "Demo", artist: "Band" } }), e);
  assert.deepEqual(e.saved.map((it) => it.values[12]), ["no_lyrics", null]);
});

/** A report about the words' timing, as the app sends it, with what it adds. */
const timing = {
  ...song,
  problem: "timed_by_hand",
  song: {
    ...song.song,
    appPackage: "org.kde.kdeconnect_tp", speed: 1, leadMs: -1450, earlyMs: 400,
    log: "1000 said 5000 at 1000 playing true speed 1.0, kept 5000 at 1000\n2000 lines in time 50590>52000",
  },
};

test("a report about timing keeps the app followed, its speed, the lead and the log", async () => {
  const e = env();
  assert.equal((await worker.fetch(post(timing), e)).status, 204);
  assert.deepEqual(e.saved[0].values.slice(12), [
    "timed_by_hand", "org.kde.kdeconnect_tp", 1, -1450, 400,
    "1000 said 5000 at 1000 playing true speed 1.0, kept 5000 at 1000\n2000 lines in time 50590>52000", null,
    null, null, null, null, null, null,
  ]);
  assert.match(e.saved[0].sql, /app_package, speed, lead_ms, early_ms, timing_log, recording, lyric_line, word, reading_shown, reader, language, right_reading\) SELECT (\?, ){24}\? WHERE /);
});

test("what timing adds that isn't what it should be is left out, and the rest is kept", () => {
  const odd = (extra) => readReport(JSON.stringify({ version: "2.22.3", song: { title: "Demo", artist: "Band", ...extra } }));
  assert.equal(odd({ appPackage: "not a package" }).appPackage, null);
  assert.equal(odd({ appPackage: "com.spotify.music" }).appPackage, "com.spotify.music");
  assert.equal(odd({ appPackage: 7 }).appPackage, null);
  assert.equal(odd({ speed: 1.100000023841858 }).speed, 1.1);
  assert.equal(odd({ speed: 0 }).speed, null);
  assert.equal(odd({ speed: 99 }).speed, null);
  assert.equal(odd({ speed: "1" }).speed, null);
  assert.equal(odd({ leadMs: 1.5 }).leadMs, null);
  assert.equal(odd({ leadMs: 25 * 60 * 60 * 1000 }).leadMs, null);
  assert.equal(odd({ earlyMs: -300 }).earlyMs, -300);
  assert.equal(odd({ log: "  " }).timingLog, null);
  assert.equal(odd({ log: { not: "text" } }).timingLog, null);
  // A log the app bounds is kept whole; a longer one is cut, not turned away.
  assert.equal(odd({ log: "x".repeat(8192) }).timingLog.length, 8192);
  assert.equal(odd({ log: "é".repeat(9000) }).timingLog.length, 8192);
});

test("a report about timing the size the app bounds it to is kept", async () => {
  const e = env();
  // 120 notes of 300 characters, cut to the 8000 the app sends, with the longest comment and 20 lines put in time.
  const log = Array.from({ length: 26 }, (_, i) => `${i}`.padEnd(299, "x")).join("\n").slice(0, 8000);
  const lines = Array.from({ length: 20 }, () => ({ line: "y".repeat(300), timedMs: 1, heardMs: 2 }));
  const big = { ...timing, comment: "c".repeat(2000), song: { ...timing.song, log, timing: lines } };
  assert.equal((await worker.fetch(post(big), e)).status, 204);
  assert.equal(e.saved[0].values[17], log);
});

/** A reading marked wrong, as the app sends it: nothing but the song, the word and how it read. Made-up words. */
const reading = {
  version: "2.25.0",
  android: "17 (37)",
  problem: "wrong_reading",
  song: { title: "Demo", artist: "Band", source: "lrclib" },
  reading: { line: "空の花 again", word: "花", shown: "か / ka", reader: "ipadic+unidic", language: "japanese", right: " はな " },
};

test("a reading marked wrong keeps the line, the word, the reading shown, what read it, the script and the right one", async () => {
  const e = env();
  assert.equal((await worker.fetch(post({ ...reading, extra: "dropped" }), e)).status, 204);
  const values = e.saved[0].values;
  assert.deepEqual(values.slice(0, 4), ["2.25.0", "17 (37)", "Demo", "Band"]);
  assert.equal(values[9], "lrclib");
  assert.equal(values[12], "wrong_reading");
  assert.deepEqual(values.slice(19), ["空の花 again", "花", "か / ka", "ipadic+unidic", "japanese", "はな"]);
});

test("a reading marked wrong needs a song, a word and the reading shown; the right one may be left out", async () => {
  const e = env();
  const send = (body) => worker.fetch(post(body), e).then((it) => it.status);
  assert.equal(await send({ ...reading, song: undefined }), 400);
  assert.equal(await send({ ...reading, reading: undefined }), 400);
  assert.equal(await send({ ...reading, reading: ["花"] }), 400);
  assert.equal(await send({ ...reading, reading: { ...reading.reading, word: " " } }), 400);
  assert.equal(await send({ ...reading, reading: { ...reading.reading, shown: 3 } }), 400);
  assert.equal(await send({ ...reading, reading: { ...reading.reading, right: undefined } }), 204);
  assert.equal(e.saved[0].values[24], null);
});

test("what a wrong reading has that isn't what it should be is left out or cut", () => {
  const odd = (extra) => readReport(JSON.stringify({ ...reading, reading: { ...reading.reading, ...extra } }));
  assert.equal(odd({ reader: "ipadic; drop table" }).reader, null);
  assert.equal(odd({ reader: "unidic" }).reader, "unidic");
  assert.equal(odd({ reader: "ipadic+" }).reader, null);
  assert.equal(odd({ language: "Japanese" }).language, null);
  assert.equal(odd({ language: "x".repeat(21) }).language, "x".repeat(20));
  assert.equal(odd({ word: "w".repeat(150) }).word.length, 100);
  assert.equal(odd({ shown: "s".repeat(400) }).shown.length, 300);
  assert.equal(odd({ line: "l".repeat(400) }).line.length, 300);
  assert.equal(odd({ right: "r".repeat(150) }).right.length, 100);
});

test("a reading sent with another problem, or none, is no wrong reading", () => {
  const other = readReport(JSON.stringify({ ...reading, problem: "no_lyrics" }));
  assert.equal(other.problem, "no_lyrics");
  assert.equal(other.word, null);
  assert.equal(readReport(JSON.stringify({ ...reading, problem: undefined })).word, null);
});

/** A song's timing an earlier version kept, as the app sends it: only what the app kept, every line it had. */
const kept = {
  version: "2.24.0",
  android: "15 (35)",
  problem: "kept_timing",
  song: {
    title: "Demo", artist: "Band", sung: true, recording: "SPOTIFY:abc123", lengthMs: 220937,
    timing: Array.from({ length: 300 }, (_, i) => ({ timedMs: i * 1000, heardMs: i * 1000 - 250 })),
  },
};

test("a song's timing kept from an earlier version is kept, with its recording and every line", async () => {
  const e = env();
  assert.equal((await worker.fetch(post(kept), e)).status, 204);
  const values = e.saved[0].values;
  assert.equal(values[12], "kept_timing");
  assert.equal(values[7], 1);
  assert.equal(values[8], 220937);
  assert.equal(values[18], "SPOTIFY:abc123");
  assert.equal(JSON.parse(values[10]).length, 300);
  // Lines with no words: the key and the points were all it kept.
  assert.deepEqual(JSON.parse(values[10])[0], { line: null, timedMs: 0, heardMs: -250 });
});

test("a recording that isn't text is left out, and a long one is cut", () => {
  const odd = (recording) => readReport(JSON.stringify({ ...kept, song: { ...kept.song, recording } })).recording;
  assert.equal(odd(7), null);
  assert.equal(odd("  "), null);
  assert.equal(odd("r".repeat(500)).length, 200);
});

test("a report is only taken as JSON, as every version of the app sends it", async () => {
  const e = env();
  const as = (type) => worker.fetch(post(song, { headers: type === undefined ? {} : { "Content-Type": type } }), e).then((it) => it.status);
  // A web page can make a browser send these to anyone without asking first.
  for (const type of ["text/plain", "text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x"]) {
    assert.equal(await as(type), 415, type);
  }
  for (const type of ["application/jsonp", "application/json, text/plain", "application/json; charset=utf-16", "application/json; charset=utf-8; x=y", "application/json-seq", "text/json", ""]) {
    assert.equal(await as(type), 415, type);
  }
  // With no type at all.
  const none = new Request("https://report.crosstune.cc/reports", { method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7" }, body: new TextEncoder().encode(JSON.stringify(song)) });
  assert.equal(none.headers.get("Content-Type"), null);
  assert.equal((await worker.fetch(none, e)).status, 415);
  // Turned away before it's counted against the sender.
  assert.deepEqual(e.keys, []);
  assert.equal(e.saved.length, 0);
  for (const type of ["application/json; charset=utf-8", "application/json", "Application/JSON;charset=UTF-8", "application/json ; charset=utf-8"]) {
    assert.equal(await as(type), 204, type);
  }
  assert.equal(e.saved.length, 4);
});

test("an IPv6 sender is counted by its /64, and an IPv4 one by its whole address", async () => {
  const e = env();
  for (const address of ["2001:db8::1", "2001:db8::2", "2001:DB8::3", "198.51.100.1", "::ffff:198.51.100.1"]) {
    await worker.fetch(post(song, { headers: { "CF-Connecting-IP": address } }), e);
  }
  assert.deepEqual(e.keys, ["2001:db8:0:0::/64", "2001:db8:0:0::/64", "2001:db8:0:0::/64", "198.51.100.1", "198.51.100.1"]);
});

/** D1 as the Worker sees it, on a real SQLite database with the table in schema.sql and what limits.sql adds. */
function realEnv() {
  const db = new DatabaseSync(":memory:");
  for (const file of ["schema.sql", "limits.sql"]) db.exec(readFileSync(new URL(`./${file}`, import.meta.url), "utf8"));
  const statements = [];
  return {
    db,
    statements,
    NETWORK_KEY: "made-up-key",
    DB: {
      prepare: (sql) => ({
        bind: (...values) => ({
          run: async () => {
            statements.push({ sql, values });
            return { meta: { changes: Number(db.prepare(sql).run(...values).changes) } };
          },
          first: async () => {
            statements.push({ sql, values });
            return db.prepare(sql).get(...values) ?? null;
          },
        }),
      }),
    },
    LIMIT: { limit: async () => ({ success: true }) },
  };
}

/** [count] made-up reports about [problem] put in the table as if they came in [ago] ("-1 days"), counted as the Worker's are. */
function fill(e, count, ago = "+0 seconds", problem = null) {
  e.db.prepare(
    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) INSERT INTO reports (version, at, problem) SELECT 'made-up', datetime('now', ?), ? FROM n"
  ).run(count, ago, problem);
}

const today = () => new Date().toISOString().slice(0, 10);
const yesterday = () => new Date(Date.now() - 86400000).toISOString().slice(0, 10);
const rows = (e) => Number(e.db.prepare("SELECT COUNT(*) AS n FROM reports").get().n);
const counted = (e, key) => e.db.prepare("SELECT n FROM report_counts WHERE key = ?").get(key)?.n ?? 0;
const refusals = (e) => Object.fromEntries(e.db.prepare("SELECT why, n FROM refusals").all().map((it) => [it.why, it.n]));
const networkDays = (e) => Number(e.db.prepare("SELECT count(*) AS n FROM network_days").get().n);

/** A report sent from [address], on carrier [asn] when one is given, so each comes from a network of its own unless a test says otherwise. */
const from = (address, body = song, headers = {}, asn = null) => post(body, { headers: { "CF-Connecting-IP": address, ...headers }, cf: asn === null ? {} : { asn } });
let networks = 0;
const anotherNetwork = () => `198.${51 + Math.floor(++networks / 250)}.${networks % 250}.1`;

const found = { version: "2.25.0", problem: "no_lyrics", song: { title: "Demo", artist: "Band" } };
const keptTiming = { version: "2.25.0", problem: "kept_timing", song: { title: "Demo", artist: "Band" } };

test("each kind has its own share of the day: 150 a person sends, 150 the app finds, 200 timings kept from before", async () => {
  const e = realEnv();
  fill(e, 149);
  fill(e, 150, "+0 seconds", "no_lyrics");
  fill(e, 199, "+0 seconds", "kept_timing");
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  const refused = await worker.fetch(from(anotherNetwork()), e);
  assert.equal(refused.status, 429);
  assert.equal(refused.headers.get("X-Crosstune-Refused"), "full");
  // Even one the old address counted and passed on.
  const passedOn = { ...e, FORWARD_KEY: "made-up-key" };
  assert.equal((await worker.fetch(from("203.0.113.7", song, { "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": "192.0.2.1" }), passedOn)).status, 429);
  // The app's finds are full too, and the timings kept from before have one place left.
  assert.equal((await worker.fetch(from(anotherNetwork(), found), e)).status, 429);
  assert.equal((await worker.fetch(from(anotherNetwork(), keptTiming), e)).status, 204);
  assert.equal((await worker.fetch(from(anotherNetwork(), keptTiming), e)).status, 429);
  assert.equal(rows(e), 500);
  assert.deepEqual(refusals(e), { "day_full:user": 2, "day_full:found": 1, "day_full:kept": 1 });
});

test("a flood of what the app finds leaves the day's room for what people send", async () => {
  const e = realEnv();
  fill(e, 150, "+0 seconds", "no_lyrics");
  fill(e, 200, "+0 seconds", "kept_timing");
  assert.equal((await worker.fetch(from(anotherNetwork(), found), e)).status, 429);
  for (let i = 0; i < 20; i++) assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
});

test("reports from another day don't count against today's", async () => {
  const e = realEnv();
  fill(e, 600, "-1 days");
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal(rows(e), 601);
});

test("the table holds at most 3000 reports of each kind, and the daily clear-out makes room again", async () => {
  const e = realEnv();
  fill(e, 1000, "-200 days");
  fill(e, 1999, "-30 days");
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  assert.equal(rows(e), 3000);
  assert.deepEqual(refusals(e), { "kept_full:user": 1 });
  // The other kinds have their own.
  assert.equal((await worker.fetch(from(anotherNetwork(), found), e)).status, 204);
  await worker.scheduled({}, e);
  assert.equal(rows(e), 2001);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
});

test("reports deleted by hand make room at once: the counts are exact", async () => {
  const e = realEnv();
  fill(e, 3000, "-30 days");
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  // The newest are kept, so ids 1 to 3000 still span the table.
  e.db.prepare("DELETE FROM reports WHERE id <= 2909").run();
  assert.equal(rows(e), 91);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal(counted(e, "all:user"), 92);
  // And the day's too: with that one, 150 came in today.
  fill(e, 149);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  e.db.prepare("DELETE FROM reports WHERE id IN (SELECT id FROM reports ORDER BY id DESC LIMIT 1)").run();
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal(counted(e, `${today()}:user`), 150);
});

test("a report's day or kind changed by hand moves its count", () => {
  const e = realEnv();
  fill(e, 2);
  e.db.exec("UPDATE reports SET at = datetime('now', '-1 days') WHERE id = 1");
  assert.equal(counted(e, `${today()}:user`), 1);
  assert.equal(counted(e, `${yesterday()}:user`), 1);
  e.db.exec("UPDATE reports SET problem = 'no_lyrics' WHERE id = 1");
  assert.equal(counted(e, "all:user"), 1);
  assert.equal(counted(e, "all:found"), 1);
  assert.equal(counted(e, `${yesterday()}:user`), 0);
  assert.equal(counted(e, `${yesterday()}:found`), 1);
  // Another column changes no count.
  e.db.exec("UPDATE reports SET title = 'Made Up' WHERE id = 2");
  assert.deepEqual(e.db.prepare("SELECT key, n FROM report_counts WHERE n > 0 ORDER BY key").all().map((it) => `${it.key}=${it.n}`),
    [`${yesterday()}:found=1`, `${today()}:user=1`, "all:found=1", "all:user=1"]);
});

test("the table's kinds are the Worker's, for every problem", () => {
  const e = realEnv();
  const problems = [null, "wrong_reading", "no_lyrics", "no_timing", "timed_by_hand", "sung_off", "no_chords", "kept_timing", "made_up"];
  for (const problem of problems) {
    const before = counted(e, `all:${kindOf(problem)}`);
    fill(e, 1, "+0 seconds", problem);
    assert.equal(counted(e, `all:${kindOf(problem)}`), before + 1, String(problem));
  }
  assert.deepEqual([...new Set(problems.map(kindOf))].sort(), ["found", "kept", "user"]);
});

test("limits.sql takes the counts from the table, and running it again changes nothing", () => {
  const e = realEnv();
  fill(e, 3, "-1 days");
  fill(e, 2, "+0 seconds", "no_lyrics");
  e.db.exec("UPDATE report_counts SET n = 77");
  e.db.exec("INSERT INTO report_counts (key, n) VALUES ('all:made-up', 5)");
  const sql = readFileSync(new URL("./limits.sql", import.meta.url), "utf8");
  e.db.exec(sql);
  e.db.exec(sql);
  assert.equal(counted(e, "all:user"), 3);
  assert.equal(counted(e, "all:found"), 2);
  assert.equal(counted(e, `${yesterday()}:user`), 3);
  assert.equal(counted(e, `${today()}:found`), 2);
  assert.equal(counted(e, "all:made-up"), 0);
  assert.equal(rows(e), 5);
  // The counts are set over what's there, never deleted and made again, so no statement can meet a row it made.
  assert.ok(!/DELETE FROM report_counts/i.test(sql));
  assert.match(sql, /ON CONFLICT \(key\) DO UPDATE SET n = excluded\.n/);
});

test("one network sends 10 reports a person sends a day, 20 the app finds and 30 timings kept from before", async () => {
  const e = realEnv();
  const send = (address, body = song) => worker.fetch(from(address, body), e);
  // Anywhere in one /24, or one /48: one network.
  for (let i = 0; i < 10; i++) assert.equal((await send(`203.0.113.${i}`)).status, 204, `report ${i}`);
  const refused = await send("203.0.113.200");
  assert.equal(refused.status, 429);
  assert.equal(refused.headers.get("X-Crosstune-Refused"), "network");
  // Each kind is counted apart, so one can't use another's room.
  for (let i = 0; i < 20; i++) assert.equal((await send(`203.0.113.${i}`, found)).status, 204, `found ${i}`);
  assert.equal((await send("203.0.113.9", found)).status, 429);
  for (let i = 0; i < 30; i++) assert.equal((await send(`203.0.113.${i}`, keptTiming)).status, 204, `kept ${i}`);
  assert.equal((await send("203.0.113.9", keptTiming)).status, 429);
  for (let i = 0; i < 10; i++) assert.equal((await send(`2001:db8:a:${i}::1`)).status, 204);
  assert.equal((await send("2001:db8:a:ffff::1")).status, 429);
  // Other networks aren't touched.
  assert.equal((await send("203.0.114.1")).status, 204);
  assert.equal((await send("2001:db8:b::1")).status, 204);
  assert.equal(rows(e), 72);
  assert.deepEqual(refusals(e), { "network:user": 2, "network:found": 1, "network:kept": 1 });
});

test("a phone sending the timings it kept from before leaves room for a problem it finds live", async () => {
  // v2.25.0 sends what it owes at each start, 4 a minute, until one is turned away; a problem found meanwhile
  // goes once, and isn't kept when it's turned away.
  const e = realEnv();
  const send = (body) => worker.fetch(from("203.0.113.7", body), e);
  let sent = 0;
  while ((await send(keptTiming)).status === 204) sent++;
  assert.equal(sent, 30);
  assert.equal((await send(found)).status, 204);
  assert.equal((await send(song)).status, 204);
});

test("an IPv6 provider's /32 sends 30 reports a person sends a day, however many /48s it has", async () => {
  const e = realEnv();
  let kept = 0;
  for (let n = 0; n < 4; n++) for (let i = 0; i < 10; i++) if ((await worker.fetch(from(`2001:db8:${n}:${i}::1`), e)).status === 204) kept++;
  assert.equal(kept, 30);
  const refused = await worker.fetch(from("2001:db8:ff::1"), e);
  assert.equal(refused.headers.get("X-Crosstune-Refused"), "block");
  assert.equal((await worker.fetch(from("2001:db9::1"), e)).status, 204);
  assert.equal((await worker.fetch(from("2001:db8:ff::1", found), e)).status, 204);
});

test("a carrier sends 50 reports a person sends a day, however many networks it has", async () => {
  const e = realEnv();
  let kept = 0;
  for (let n = 0; n < 6; n++) {
    const network = anotherNetwork();
    for (let i = 0; i < 10; i++) if ((await worker.fetch(from(network, song, {}, 64500), e)).status === 204) kept++;
  }
  assert.equal(kept, 50);
  assert.equal((await worker.fetch(from(anotherNetwork(), song, {}, 64500), e)).headers.get("X-Crosstune-Refused"), "carrier");
  assert.equal((await worker.fetch(from(anotherNetwork(), song, {}, 64501), e)).status, 204);
  assert.deepEqual(refusals(e), { "carrier:user": 11 });
});

/**
 * Fills a kind's share of the day as someone with as many networks and carriers as they like would: a new network
 * when one is turned away, a new carrier when its carrier is. How many of each it took.
 */
async function flood(e, body) {
  let carrier = 64600;
  let network = anotherNetwork();
  const networksUsed = new Set();
  const carriersUsed = new Set();
  for (;;) {
    const answer = await worker.fetch(from(network, body, {}, carrier), e);
    if (answer.status === 204) {
      networksUsed.add(network);
      carriersUsed.add(carrier);
      continue;
    }
    const why = answer.headers.get("X-Crosstune-Refused");
    if (why === "full") return { networks: networksUsed.size, carriers: carriersUsed.size };
    if (why === "carrier") carrier++;
    network = anotherNetwork();
  }
}

test("filling a kind's day takes 3 carriers and 15, 9 or 8 networks", async () => {
  // At least the share over a carrier's day (150/50, 150/50, 200/70) and over a network's (150/10, 150/20, 200/30).
  assert.deepEqual(await flood(realEnv(), song), { networks: 15, carriers: 3 });
  assert.deepEqual(await flood(realEnv(), found), { networks: 9, carriers: 3 });
  assert.deepEqual(await flood(realEnv(), keptTiming), { networks: 8, carriers: 3 });
});

test("a network is counted under keys that keep no address and are other ones each day", async () => {
  const e = realEnv();
  await worker.fetch(from("203.0.113.7", song, {}, 64500), e);
  await worker.fetch(from("2001:db8:a::1"), e);
  // The IPv4 network and its carrier, the IPv6 network and its /32.
  const kept = e.db.prepare("SELECT key, day, n FROM network_days").all();
  assert.equal(kept.length, 4);
  for (const { key, day } of kept) {
    assert.match(key, /^[0-9a-f]{64}$/);
    assert.equal(day, today());
  }
  const day = "2026-11-11";
  const key = await networkDayKey("made-up-key", day, "user", "203.0.113.0/24");
  assert.notEqual(await networkDayKey("made-up-key", "2026-11-12", "user", "203.0.113.0/24"), key);
  assert.notEqual(await networkDayKey("another-made-up-key", day, "user", "203.0.113.0/24"), key);
  assert.notEqual(await networkDayKey("made-up-key", day, "found", "203.0.113.0/24"), key);
  assert.notEqual(await networkDayKey("made-up-key", day, "user", "203.0.114.0/24"), key);
  const stored = JSON.stringify(e.db.prepare("SELECT * FROM network_days").all());
  for (const said of ["203.0.113", "2001:db8", "64500"]) assert.ok(!stored.includes(said), said);
});

test("a report passed on by the old address counts against its sender's network and carrier", async () => {
  const e = { ...realEnv(), FORWARD_KEY: "made-up-key" };
  // Where reports are kept now sees Cloudflare's own carrier for them.
  const passed = (sender, carrier = "64500") => worker.fetch(from("198.18.0.1", song, { "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": sender, "X-Crosstune-Carrier": carrier }, 13335), e);
  for (let i = 0; i < 10; i++) assert.equal((await passed(`192.0.2.${i}`)).status, 204);
  assert.equal((await passed("192.0.2.99")).headers.get("X-Crosstune-Refused"), "network");
  for (let n = 3; n < 7; n++) for (let i = 0; i < 10; i++) await passed(`192.0.${n}.${i}`);
  assert.equal((await passed("192.0.9.1")).headers.get("X-Crosstune-Refused"), "carrier");
  assert.equal((await passed("192.0.9.1", "64501")).status, 204);
  // A sender or carrier said by anyone else is not believed.
  const lying = await worker.fetch(from("192.0.2.50", song, { "X-Crosstune-Sender": "192.0.9.9", "X-Crosstune-Carrier": "64999" }, 64500), e);
  assert.equal(lying.headers.get("X-Crosstune-Refused"), "network");
  const odd = await worker.fetch(from("198.18.0.1", song, { "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": "192.0.20.1", "X-Crosstune-Carrier": "not a number" }), e);
  assert.equal(odd.status, 204);
});

test("without NETWORK_KEY nothing is kept, the log says why, and the refusal is counted", async (t) => {
  const e = { ...realEnv(), NETWORK_KEY: undefined };
  const said = t.mock.method(console, "error", () => {});
  const refused = await worker.fetch(from("203.0.113.7"), e);
  assert.equal(refused.status, 503);
  assert.equal(refused.headers.get("X-Crosstune-Refused"), "no_key");
  assert.equal(rows(e), 0);
  assert.match(said.mock.calls[0].arguments[0], /NETWORK_KEY/);
  assert.deepEqual(refusals(e), { no_key: 1 });
  // A report that isn't one is still a 400, and costs no count.
  assert.equal((await worker.fetch(from("203.0.113.7", "not json"), e)).status, 400);
  assert.deepEqual(refusals(e), { no_key: 1 });
});

test("refusals are counted up to 1000 a day for each reason, then cost no write", async () => {
  const e = realEnv();
  fill(e, 150);
  e.db.exec("INSERT INTO refusals (day, why, n) VALUES (date('now'), 'day_full:user', 998)");
  // Each one is turned away: the first two are counted, and the rest change nothing.
  for (let i = 0; i < 5; i++) assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  assert.deepEqual(refusals(e), { "day_full:user": 1000 });
});

/** Rows SQLite changed since the database was made, by the Worker's statements and the triggers they set off. */
const changed = (e) => Number(e.db.prepare("SELECT total_changes() AS n").get().n);

test("once the day or the table is full, a report writes only its refusal, and no network's count", async () => {
  const e = realEnv();
  fill(e, 150);
  const before = changed(e);
  for (let i = 0; i < 50; i++) assert.equal((await worker.fetch(from(anotherNetwork(), song, {}, 64500), e)).status, 429);
  assert.equal(networkDays(e), 0);
  assert.deepEqual(refusals(e), { "day_full:user": 50 });
  assert.equal(changed(e) - before, 50);
  const full = realEnv();
  fill(full, 3000, "-30 days");
  for (let i = 0; i < 50; i++) assert.equal((await worker.fetch(from(anotherNetwork()), full)).status, 429);
  assert.equal(networkDays(full), 0);
  assert.deepEqual(refusals(full), { "kept_full:user": 50 });
});

test("a flood from many networks writes the networks of the reports kept, and at most 1000 refusals a reason", async () => {
  const e = realEnv();
  const before = changed(e);
  for (let i = 0; i < 2000; i++) await worker.fetch(from(anotherNetwork()), e);
  assert.equal(rows(e), 150);
  assert.equal(networkDays(e), 150);
  assert.deepEqual(refusals(e), { "day_full:user": 1000 });
  // Each report kept: its network, itself and two counts. Each refusal past 1000 changes nothing.
  assert.equal(changed(e) - before, 150 * 4 + 1000);
});

test("the daily clear-out forgets past days' networks, old day counts and old refusals", async () => {
  const e = realEnv();
  await worker.fetch(from("203.0.113.7"), e);
  fill(e, 1, "-1 days");
  e.db.exec("INSERT INTO network_days (key, day, n) VALUES ('old', date('now', '-1 days'), 3)");
  e.db.exec("INSERT INTO report_counts (key, n) VALUES (date('now', '-2 days') || ':user', 4)");
  e.db.exec("INSERT INTO refusals (day, why, n) VALUES (date('now', '-181 days'), 'network:user', 1), (date('now', '-179 days'), 'network:user', 2)");
  await worker.scheduled({}, e);
  assert.deepEqual(e.db.prepare("SELECT day FROM network_days").all().map((it) => it.day), [today()]);
  // Today's and yesterday's counts stay, and what the table holds.
  assert.deepEqual(e.db.prepare("SELECT key FROM report_counts ORDER BY key").all().map((it) => it.key), [`${yesterday()}:user`, `${today()}:user`, "all:user"]);
  assert.equal(counted(e, "all:user"), 2);
  // Rows by day, so one reason's two days can't pass for one.
  const days = e.db.prepare("SELECT day, n FROM refusals ORDER BY day").all().map((it) => `${it.day}=${it.n}`);
  assert.deepEqual(days, [`${new Date(Date.now() - 179 * 86400000).toISOString().slice(0, 10)}=2`]);
});

test("a kept report is kept whole in the real table, with what it says", async () => {
  const e = realEnv();
  assert.equal((await worker.fetch(post(reading), e)).status, 204);
  const kept = e.db.prepare("SELECT version, title, problem, lyric_line, word, right_reading FROM reports").all();
  assert.deepEqual(kept.map((it) => ({ ...it })), [{ version: "2.25.0", title: "Demo", problem: "wrong_reading", lyric_line: "空の花 again", word: "花", right_reading: "はな" }]);
});

test("every statement a report costs looks rows up, never reads the table through, kept or turned away", async () => {
  const e = realEnv();
  fill(e, 149);
  // Kept, then turned away for the day, then for the network, with a carrier and a /32 counted too.
  for (let i = 0; i < 12; i++) await worker.fetch(from("2001:db8::1", song, {}, 64500), e);
  for (let i = 0; i < 12; i++) await worker.fetch(from("2001:db8::1", found, {}, 64500), e);
  fill(e, 3000, "-30 days", "kept_timing");
  await worker.fetch(from("2001:db8::1", keptTiming, {}, 64500), e);
  // Reading the limits, counting the network, keeping the report, counting a refusal, for each kind.
  const asked = new Set(e.statements.map((it) => it.sql));
  assert.equal(asked.size, 7);
  for (const { sql, values } of e.statements) {
    const plan = e.db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...values).map((it) => it.detail);
    // SCAN reads every row; SEARCH goes straight to the rows it wants.
    assert.deepEqual(plan.filter((it) => /\bSCAN\b/.test(it) && !/CONSTANT ROW/.test(it)), [], `${sql}\n${plan.join("\n")}`);
  }
});

test("control characters and the marks that turn text around are never kept", async () => {
  const e = env();
  const odd = "a\u001b[31mred\u0007\u0000\u007f\u0085\u009b2J‮evil⁦x⁩‪‫‬‭";
  const body = {
    ...timing,
    comment: `${odd}\r\nnext\ttab after end`,
    song: { ...timing.song, title: `Demo${odd}\nline`, artist: "\u001b\u0007 ", log: "1000 said\r\n2000 lines\u001b[2K", timing: [{ line: `one\u001b]0;x\u0007\ntwo`, timedMs: 1, heardMs: 2 }] },
  };
  assert.equal((await worker.fetch(post(body), e)).status, 400);
  assert.equal((await worker.fetch(post({ ...body, song: { ...body.song, artist: "Band‮" } }), e)).status, 204);
  const values = e.saved[0].values;
  // Only a comment and the log keep their lines; elsewhere a break or a tab is a space.
  assert.equal(values[11], "a[31mred2Jevilx\nnext tab\nafter\nend");
  assert.equal(values[2], "Demoa[31mred2Jevilx line");
  assert.equal(values[3], "Band");
  assert.equal(values[17], "1000 said\n2000 lines[2K");
  assert.deepEqual(JSON.parse(values[10]), [{ line: "one]0;x two", timedMs: 1, heardMs: 2 }]);
  const word = readReport(JSON.stringify({ ...reading, reading: { ...reading.reading, word: "花\u001b", shown: "‮か", right: "\u0007", line: "a\nb" } }));
  assert.deepEqual([word.word, word.shown, word.right, word.line], ["花", "か", null, "a b"]);
  // Cut to its longest after they're gone, so they take no room.
  assert.equal(readReport(JSON.stringify({ version: "2.25.0", comment: "\u001b".repeat(5000) + "x".repeat(2000) })).comment.length, 2000);
});
