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

/** A request, with what Cloudflare tells of its sender in [cf]. */
function post(body, { path = "/reports", method = "POST", headers = {}, cf = { asn: 64500 } } = {}) {
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

/**
 * D1 as the Worker sees it, on a real SQLite database with the table in schema.sql and what limits.sql adds. When
 * [slow], each statement waits for the next turn of the event loop before it runs, and its answer for the one after,
 * as one to D1 waits on the network: reports sent at once then run their statements in between each other's.
 */
function realEnv({ slow = false } = {}) {
  const db = new DatabaseSync(":memory:");
  for (const file of ["schema.sql", "limits.sql"]) db.exec(readFileSync(new URL(`./${file}`, import.meta.url), "utf8"));
  const statements = [];
  const away = () => (slow ? new Promise(setImmediate) : null);
  return {
    db,
    statements,
    NETWORK_KEY: "made-up-key",
    DB: {
      prepare: (sql) => ({
        bind: (...values) => ({
          run: async () => {
            await away();
            statements.push({ sql, values });
            const changes = Number(db.prepare(sql).run(...values).changes);
            await away();
            return { meta: { changes } };
          },
          first: async () => {
            await away();
            statements.push({ sql, values });
            const row = db.prepare(sql).get(...values) ?? null;
            await away();
            return row;
          },
        }),
      }),
    },
    LIMIT: { limit: async () => ({ success: true }) },
  };
}

/**
 * [count] made-up reports about [problem] put in the table as if they came in [ago] ("-1 days"), counted as the
 * Worker's are, each with a comment of [size] characters.
 */
function fill(e, count, ago = "+0 seconds", problem = null, size = 0) {
  e.db.prepare(
    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) INSERT INTO reports (version, at, problem, comment) SELECT 'made-up', datetime('now', ?), ?, nullif(substr(hex(zeroblob(?)), 1, ?), '') FROM n"
  ).run(count, ago, problem, Math.ceil(size / 2), size);
}

const MB = 1_000_000;
const today = () => new Date().toISOString().slice(0, 10);
const yesterday = () => new Date(Date.now() - 86400000).toISOString().slice(0, 10);
const rows = (e) => Number(e.db.prepare("SELECT COUNT(*) AS n FROM reports").get().n);
const counted = (e, key) => e.db.prepare("SELECT n FROM report_counts WHERE key = ?").get(key)?.n ?? 0;
const bytes = (e, kind) => counted(e, `bytes:${kind}`);
const refusals = (e) => Object.fromEntries(e.db.prepare("SELECT why, n FROM refusals").all().map((it) => [it.why, it.n]));
const networkDays = (e) => Number(e.db.prepare("SELECT count(*) AS n FROM network_days").get().n);
/**
 * Every count the Worker asks, as limits.sql keeps them, and as it takes them again from the table: the bytes of each
 * kind, today's and yesterday's. Older days wait for the daily clear-out.
 */
const ASKED = "n != 0 AND (key LIKE 'bytes:%' OR key >= date('now', '-1 day'))";
const counts = (e) => e.db.prepare(`SELECT key, n FROM report_counts WHERE ${ASKED} ORDER BY key`).all().map((it) => `${it.key}=${it.n}`);
const recounted = (e) => {
  const copy = new DatabaseSync(":memory:");
  copy.exec(readFileSync(new URL("./schema.sql", import.meta.url), "utf8"));
  const columns = e.db.prepare("SELECT name FROM pragma_table_info('reports')").all().map((it) => it.name).join(", ");
  const insert = copy.prepare(`INSERT INTO reports (${columns}) VALUES (${columns.split(", ").map(() => "?").join(", ")})`);
  for (const row of e.db.prepare(`SELECT ${columns} FROM reports`).all()) insert.run(...Object.values(row));
  copy.exec(readFileSync(new URL("./limits.sql", import.meta.url), "utf8"));
  return counts({ db: copy });
};
/** As if the table held [held] bytes of [kind]. */
const hold = (e, kind, held) => e.db.prepare("INSERT INTO report_counts (key, n) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET n = excluded.n").run(`bytes:${kind}`, held);

/** A report sent from [address], so each comes from a network of its own unless a test says otherwise. */
const from = (address, body = song, headers = {}) => post(body, { headers: { "CF-Connecting-IP": address, ...headers } });
let networks = 0;
const anotherNetwork = () => `198.${51 + Math.floor(++networks / 250)}.${networks % 250}.1`;

const found = { version: "2.25.0", problem: "no_lyrics", song: { title: "Demo", artist: "Band" } };
const keptTiming = { version: "2.25.0", problem: "kept_timing", song: { title: "Demo", artist: "Band" } };

test("each kind has its own share of the day: 500 a person sends, 2500 the app finds, 3000 timings kept from before", async () => {
  const e = realEnv();
  fill(e, 499);
  fill(e, 2500, "+0 seconds", "no_lyrics");
  fill(e, 2999, "+0 seconds", "kept_timing");
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
  assert.equal(rows(e), 6000);
  assert.deepEqual(refusals(e), { "day_full:user": 2, "day_full:found": 1, "day_full:kept": 1 });
});

test("a flood of what the app finds leaves the day's room for what people send", async () => {
  const e = realEnv();
  fill(e, 2500, "+0 seconds", "no_lyrics");
  fill(e, 3000, "+0 seconds", "kept_timing");
  assert.equal((await worker.fetch(from(anotherNetwork(), found), e)).status, 429);
  for (let i = 0; i < 20; i++) assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
});

test("reports from another day don't count against today's", async () => {
  const e = realEnv();
  fill(e, 600, "-1 days");
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal(rows(e), 601);
});

test("the table holds at most 50 MB of what people send, counted to the byte, and the daily clear-out makes room again", async () => {
  const e = realEnv();
  // The biggest reports there are, half of them past the days a report is kept: about 48 kB each.
  fill(e, 1, "-200 days", null, 48 * 1024);
  const biggest = bytes(e, "user");
  const most = Math.floor((50 * MB - 4000) / biggest);
  fill(e, Math.floor(most / 2) - 1, "-200 days", null, 48 * 1024);
  fill(e, most - Math.floor(most / 2), "-30 days", null, 48 * 1024);
  assert.equal(bytes(e, "user"), most * biggest);
  let sent = 0;
  let answer;
  while ((answer = await worker.fetch(from(anotherNetwork()), e)).status === 204) sent++;
  assert.equal(answer.headers.get("X-Crosstune-Refused"), "full");
  assert.ok(sent > 0);
  // Kept until the table held 50 MB, and not one more.
  const held = bytes(e, "user");
  assert.ok(held >= 50 * MB && held - (held - most * biggest) / sent < 50 * MB, String(held));
  assert.deepEqual(refusals(e), { "kept_full:user": 1 });
  // The other kinds have their own.
  assert.equal((await worker.fetch(from(anotherNetwork(), found), e)).status, 204);
  await worker.scheduled({}, e);
  assert.equal(rows(e), most - Math.floor(most / 2) + sent + 1);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.deepEqual(counts(e), recounted(e));
});

test("the app's finds and the timings kept from before hold 150 MB each of the table", async () => {
  for (const [body, kind] of [[found, "found"], [keptTiming, "kept"]]) {
    const e = realEnv();
    hold(e, kind, 150 * MB - 1);
    assert.equal((await worker.fetch(from(anotherNetwork(), body), e)).status, 204, kind);
    const refused = await worker.fetch(from(anotherNetwork(), body), e);
    assert.equal(refused.headers.get("X-Crosstune-Refused"), "full", kind);
    assert.deepEqual(refusals(e), { [`kept_full:${kind}`]: 1 });
    // What people send has its own.
    assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204, kind);
  }
});

test("what's counted is what the reports take in the database, so the shares, 350 MB in all, fit in D1's 500 MB", () => {
  // Reports like the real ones, about 500 bytes, and the biggest the Worker takes.
  for (const [count, size] of [[20000, 400], [400, 48 * 1024]]) {
    const e = realEnv();
    fill(e, count, "+0 seconds", "no_lyrics", size);
    const pages = Number(e.db.prepare("PRAGMA page_count").get().page_count) * Number(e.db.prepare("PRAGMA page_size").get().page_size);
    const ratio = pages / bytes(e, "found");
    assert.ok(ratio > 0.9 && ratio < 1.1, `${count} reports of ${size}: ${ratio}`);
    assert.ok(350 * MB * ratio < 500 * MB);
  }
});

test("reports deleted by hand make room at once: the counts are exact", async () => {
  const e = realEnv();
  fill(e, 1050, "-30 days", null, 48 * 1024);
  fill(e, 499);
  assert.ok(bytes(e, "user") >= 50 * MB);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  e.db.prepare("DELETE FROM reports WHERE id <= 100").run();
  assert.ok(bytes(e, "user") < 50 * MB);
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  // And the day's too: with that one, 500 came in today.
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  e.db.prepare("DELETE FROM reports WHERE id IN (SELECT id FROM reports ORDER BY id DESC LIMIT 1)").run();
  assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 204);
  assert.equal(counted(e, `${today()}:user`), 500);
  assert.deepEqual(counts(e), recounted(e));
});

test("a report's day, kind or size changed by hand moves its counts", () => {
  const e = realEnv();
  fill(e, 2);
  const one = bytes(e, "user") / 2;
  e.db.exec("UPDATE reports SET at = datetime('now', '-1 days') WHERE id = 1");
  assert.equal(counted(e, `${today()}:user`), 1);
  assert.equal(counted(e, `${yesterday()}:user`), 1);
  e.db.exec("UPDATE reports SET problem = 'no_lyrics' WHERE id = 1");
  assert.equal(bytes(e, "user"), one);
  assert.equal(bytes(e, "found"), one + "no_lyrics".length);
  assert.equal(counted(e, `${yesterday()}:user`), 0);
  assert.equal(counted(e, `${yesterday()}:found`), 1);
  // Another column changes only the size.
  e.db.exec("UPDATE reports SET title = 'Made Up' WHERE id = 2");
  assert.equal(bytes(e, "user"), one + "Made Up".length);
  assert.deepEqual(counts(e), recounted(e));
});

test("the table's kinds are the Worker's, for every problem", () => {
  const e = realEnv();
  const problems = [null, "wrong_reading", "no_lyrics", "no_timing", "timed_by_hand", "sung_off", "no_chords", "kept_timing", "made_up"];
  for (const problem of problems) {
    const before = counted(e, `${today()}:${kindOf(problem)}`);
    fill(e, 1, "+0 seconds", problem);
    assert.equal(counted(e, `${today()}:${kindOf(problem)}`), before + 1, String(problem));
  }
  assert.deepEqual([...new Set(problems.map(kindOf))].sort(), ["found", "kept", "user"]);
  assert.deepEqual(counts(e), recounted(e));
});

test("limits.sql takes the counts from the table, and running it again changes nothing", () => {
  const e = realEnv();
  fill(e, 3, "-1 days");
  fill(e, 2, "+0 seconds", "no_lyrics");
  const right = counts(e);
  e.db.exec("UPDATE report_counts SET n = 77");
  e.db.exec("INSERT INTO report_counts (key, n) VALUES ('bytes:made-up', 5)");
  const sql = readFileSync(new URL("./limits.sql", import.meta.url), "utf8");
  e.db.exec(sql);
  e.db.exec(sql);
  assert.deepEqual(counts(e), right);
  assert.equal(counted(e, `${yesterday()}:user`), 3);
  assert.equal(counted(e, `${today()}:found`), 2);
  assert.equal(counted(e, "bytes:made-up"), 0);
  assert.equal(rows(e), 5);
  // The counts are set over what's there, never deleted and made again, so no statement can meet a row it made.
  assert.ok(!/DELETE FROM report_counts/i.test(sql));
  assert.match(sql, /ON CONFLICT \(key\) DO UPDATE SET n = excluded\.n/);
});

test("one network sends 10 reports a person sends a day, 70 the app finds and 200 timings kept from before", async () => {
  // Room for 3 phones on one home's or carrier's network on their busiest day: one phone sent 3 of its own, and found
  // 22 problems, in a day.
  const e = realEnv();
  const send = (address, body = song) => worker.fetch(from(address, body), e);
  // Anywhere in one /24, or one /48: one network.
  for (let i = 0; i < 10; i++) assert.equal((await send(`203.0.113.${i}`)).status, 204, `report ${i}`);
  const refused = await send("203.0.113.200");
  assert.equal(refused.status, 429);
  assert.equal(refused.headers.get("X-Crosstune-Refused"), "network");
  // Each kind is counted apart, so one can't use another's room.
  for (let i = 0; i < 70; i++) assert.equal((await send(`203.0.113.${i}`, found)).status, 204, `found ${i}`);
  assert.equal((await send("203.0.113.9", found)).status, 429);
  for (let i = 0; i < 200; i++) assert.equal((await send(`203.0.113.${i}`, keptTiming)).status, 204, `kept ${i}`);
  assert.equal((await send("203.0.113.9", keptTiming)).status, 429);
  for (let i = 0; i < 10; i++) assert.equal((await send(`2001:db8:a:${i}::1`)).status, 204);
  assert.equal((await send("2001:db8:a:ffff::1")).status, 429);
  // Other networks aren't touched, in the same IPv6 provider's /32 too.
  assert.equal((await send("203.0.114.1")).status, 204);
  assert.equal((await send("2001:db8:b::1")).status, 204);
  assert.equal(rows(e), 292);
  assert.deepEqual(refusals(e), { "network:user": 2, "network:found": 1, "network:kept": 1 });
});

test("reports sent at once from one network keep exactly its day's", async () => {
  const e = realEnv({ slow: true });
  const answers = await Promise.all(Array.from({ length: 60 }, (_, i) => worker.fetch(from(`203.0.113.${i}`), e)));
  assert.equal(answers.filter((it) => it.status === 204).length, 10);
  assert.ok(answers.filter((it) => it.status === 429).every((it) => it.headers.get("X-Crosstune-Refused") === "network"));
  // Every report read the network's count before any was counted, so this was the count's own guard.
  const asked = e.statements.map((it) => (it.sql.startsWith("SELECT") ? "check" : it.sql.startsWith("INSERT INTO network_days") ? "count" : "other"));
  assert.ok(asked.lastIndexOf("check") < asked.indexOf("count"));
  assert.equal(rows(e), 10);
  assert.deepEqual(e.db.prepare("SELECT n FROM network_days").all().map((it) => it.n), [10]);
});

test("reports that race for the day's last place leave no network's count behind", async () => {
  const e = realEnv({ slow: true });
  fill(e, 499);
  const before = changed(e);
  const answers = await Promise.all(Array.from({ length: 60 }, () => worker.fetch(from(anotherNetwork()), e)));
  assert.equal(answers.filter((it) => it.status === 204).length, 1);
  assert.ok(answers.filter((it) => it.status === 429).every((it) => it.headers.get("X-Crosstune-Refused") === "full"));
  assert.equal(rows(e), 500);
  // Each counted its network before the day was full; the 59 turned away took theirs back.
  assert.equal(networkDays(e), 1);
  assert.deepEqual(refusals(e), { "day_full:user": 59 });
  // The one kept: its network, itself, two counts. Each turned away: its network counted, taken back and deleted, and a refusal.
  assert.equal(changed(e) - before, 4 + 59 * 4);
});

test("a phone sending the timings it kept from before leaves room for a problem it finds live", async () => {
  // v2.25.0 sends what it owes at each start, 4 a minute, until one is turned away; a problem found meanwhile goes
  // at once, and from v2.26.0 is kept for a later start when it's turned away.
  const e = realEnv();
  const send = (body) => worker.fetch(from("203.0.113.7", body), e);
  let sent = 0;
  while ((await send(keptTiming)).status === 204) sent++;
  assert.equal(sent, 200);
  assert.equal((await send(found)).status, 204);
  assert.equal((await send(song)).status, 204);
});

/** Fills a kind's share of the day as someone with as many networks as they like would: a new one when one is turned away. How many it took. */
async function flood(e, body) {
  let network = anotherNetwork();
  const used = new Set();
  for (;;) {
    const answer = await worker.fetch(from(network, body), e);
    if (answer.status === 204) {
      used.add(network);
      continue;
    }
    if (answer.headers.get("X-Crosstune-Refused") === "full") return used.size;
    network = anotherNetwork();
  }
}

test("filling a kind's day takes 50, 36 or 15 networks", async () => {
  // The share over a network's day: 500/10, 2500/70 and 3000/200.
  assert.equal(await flood(realEnv(), song), 50);
  assert.equal(await flood(realEnv(), found), 36);
  assert.equal(await flood(realEnv(), keptTiming), 15);
});

test("a network is counted under keys that keep no address and are other ones each day", async () => {
  const e = realEnv();
  await worker.fetch(from("203.0.113.7"), e);
  await worker.fetch(from("2001:db8:a::1"), e);
  const kept = e.db.prepare("SELECT key, day, n FROM network_days").all();
  assert.equal(kept.length, 2);
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
  for (const said of ["203.0.113", "2001:db8"]) assert.ok(!stored.includes(said), said);
});

test("a report passed on by the old address counts against its sender's network; a sender said without the key isn't believed", async () => {
  const e = { ...realEnv(), FORWARD_KEY: "made-up-key" };
  const passed = (sender) => worker.fetch(from("198.18.0.1", song, { "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": sender }), e);
  for (let i = 0; i < 10; i++) assert.equal((await passed(`192.0.2.${i}`)).status, 204);
  assert.equal((await passed("192.0.2.99")).headers.get("X-Crosstune-Refused"), "network");
  assert.equal((await passed("192.0.3.1")).status, 204);
  // Naming the full network from a network of its own, without the key or with another: its own network is counted, and has room.
  for (const key of [{}, { "X-Crosstune-Passed-On": "another-made-up-key" }]) {
    const named = await worker.fetch(from(anotherNetwork(), song, { ...key, "X-Crosstune-Sender": "192.0.2.1" }), e);
    assert.equal(named.status, 204, JSON.stringify(key));
  }
  // And naming a network with room from the full one doesn't get its room.
  const lying = await worker.fetch(from("192.0.2.50", song, { "X-Crosstune-Sender": "192.0.9.9" }), e);
  assert.equal(lying.headers.get("X-Crosstune-Refused"), "network");
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
  fill(e, 500);
  e.db.exec("INSERT INTO refusals (day, why, n) VALUES (date('now'), 'day_full:user', 998)");
  // Each one is turned away: the first two are counted, and the rest change nothing.
  for (let i = 0; i < 5; i++) assert.equal((await worker.fetch(from(anotherNetwork()), e)).status, 429);
  assert.deepEqual(refusals(e), { "day_full:user": 1000 });
});

/** Rows SQLite changed since the database was made, by the Worker's statements and the triggers they set off. */
const changed = (e) => Number(e.db.prepare("SELECT total_changes() AS n").get().n);

test("once the day or the table is full, a report writes only its refusal, and no network's count, sent apart or at once", async () => {
  for (const slow of [false, true]) {
    const e = realEnv({ slow });
    fill(e, 500);
    const before = changed(e);
    const send = () => worker.fetch(from(anotherNetwork()), e);
    const answers = slow ? await Promise.all(Array.from({ length: 50 }, send)) : await Array.from({ length: 50 }).reduce(async (all) => [...(await all), await send()], []);
    assert.ok(answers.every((it) => it.status === 429));
    assert.equal(networkDays(e), 0);
    assert.deepEqual(refusals(e), { "day_full:user": 50 });
    assert.equal(changed(e) - before, 50);
    const full = realEnv({ slow });
    hold(full, "user", 50 * MB);
    const fullBefore = changed(full);
    const turned = await Promise.all(Array.from({ length: 50 }, () => worker.fetch(from(anotherNetwork()), full)));
    assert.ok(turned.every((it) => it.status === 429));
    assert.equal(networkDays(full), 0);
    assert.deepEqual(refusals(full), { "kept_full:user": 50 });
    assert.equal(changed(full) - fullBefore, 50);
  }
});

test("a flood from many networks writes the networks of the reports kept, and at most 1000 refusals a reason", async () => {
  const e = realEnv();
  const before = changed(e);
  for (let i = 0; i < 2000; i++) await worker.fetch(from(anotherNetwork()), e);
  assert.equal(rows(e), 500);
  assert.equal(networkDays(e), 500);
  assert.deepEqual(refusals(e), { "day_full:user": 1000 });
  // Each report kept: its network, itself and two counts. Each refusal past 1000 changes nothing.
  assert.equal(changed(e) - before, 500 * 4 + 1000);
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
  assert.deepEqual(e.db.prepare("SELECT key FROM report_counts ORDER BY key").all().map((it) => it.key), [`${yesterday()}:user`, `${today()}:user`, "bytes:user"]);
  assert.deepEqual(counts(e), recounted(e));
  // Rows by day, so one reason's two days can't pass for one.
  const days = e.db.prepare("SELECT day, n FROM refusals ORDER BY day").all().map((it) => `${it.day}=${it.n}`);
  assert.deepEqual(days, [`${new Date(Date.now() - 179 * 86400000).toISOString().slice(0, 10)}=2`]);
});

test("a delete of the daily clear-out that fails doesn't stop the others, and the run fails after, with its error", async (t) => {
  const e = realEnv();
  fill(e, 1, "-200 days");
  e.db.exec("INSERT INTO network_days (key, day, n) VALUES ('old', date('now', '-1 days'), 3)");
  e.db.exec("INSERT INTO refusals (day, why, n) VALUES (date('now', '-181 days'), 'network:user', 1)");
  const said = t.mock.method(console, "error", () => {});
  const broken = new Error("D1 is down");
  // The first delete, of old reports, fails; so does the third.
  const failing = { ...e, DB: { prepare: (sql) => (/^DELETE FROM (reports|report_counts) /.test(sql) ? { bind: () => ({ run: async () => { throw broken; } }) } : e.DB.prepare(sql)) } };
  await assert.rejects(worker.scheduled({}, failing), (error) => error instanceof AggregateError && error.errors.length === 2 && error.errors.every((it) => it === broken));
  assert.equal(networkDays(e), 0);
  assert.deepEqual(refusals(e), {});
  assert.equal(rows(e), 1);
  assert.equal(said.mock.callCount(), 2);
  assert.match(said.mock.calls[0].arguments[0], /DELETE FROM reports/);
});

test("a kept report is kept whole in the real table, with what it says", async () => {
  const e = realEnv();
  assert.equal((await worker.fetch(post(reading), e)).status, 204);
  const kept = e.db.prepare("SELECT version, title, problem, lyric_line, word, right_reading FROM reports").all();
  assert.deepEqual(kept.map((it) => ({ ...it })), [{ version: "2.25.0", title: "Demo", problem: "wrong_reading", lyric_line: "空の花 again", word: "花", right_reading: "はな" }]);
});

test("every statement a report costs looks rows up, never reads the table through, kept or turned away", async () => {
  const e = realEnv({ slow: true });
  fill(e, 499);
  // Two at once for the day's last place: one kept, and the other's network taken back and turned away.
  await Promise.all([worker.fetch(from(anotherNetwork()), e), worker.fetch(from(anotherNetwork()), e)]);
  // Kept, then turned away for the network.
  for (let i = 0; i < 71; i++) await worker.fetch(from("2001:db8::1", found), e);
  await worker.fetch(from("2001:db8::1", keptTiming), e);
  hold(e, "kept", 150 * MB);
  await worker.fetch(from("2001:db8::1", keptTiming), e);
  // For each kind: reading the limits, keeping the report. Once: counting the network, taking it back, and a refusal.
  const asked = new Set(e.statements.map((it) => it.sql));
  assert.equal(asked.size, 10);
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
