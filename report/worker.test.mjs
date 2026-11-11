// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import * as all from "./worker.js";
import worker, { readReport } from "./worker.js";

/** D1 and the rate limit, as a test needs them: what was saved, and whether the limit lets one through. */
function env({ allowed = true } = {}) {
  const saved = [];
  const keys = [];
  return {
    saved,
    keys,
    DB: {
      prepare: (sql) => ({ bind: (...values) => ({ run: async () => (saved.push({ sql, values }), { meta: { changes: 1 } }) }) }),
    },
    LIMIT: { limit: async ({ key }) => (keys.push(key), { success: allowed }) },
  };
}

function post(body, { path = "/reports", method = "POST", headers = {} } = {}) {
  return new Request(`https://report.crosstune.cc${path}`, {
    method,
    // What every version of the app sends, through OkHttp.
    headers: { "CF-Connecting-IP": "203.0.113.7", "Content-Type": "application/json; charset=utf-8", ...headers },
    body: method === "GET" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
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
  // Too many lines put in time is no report.
  const many = Array.from({ length: 301 }, () => ({ line: "x", timedMs: 0, heardMs: 0 }));
  assert.equal(readReport(JSON.stringify({ version: "2.12.0", song: { title: "Demo", artist: "Band", timing: many } })), null);
});

test("reports older than the policy allows are deleted", async () => {
  const keeping = env();
  await worker.scheduled({}, keeping);
  assert.deepEqual(keeping.saved, [{ sql: "DELETE FROM reports WHERE at < datetime('now', ?)", values: ["-180 days"] }]);
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

/** D1 as the Worker sees it, on a real SQLite database with the table in schema.sql. */
function realEnv() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("./schema.sql", import.meta.url), "utf8"));
  const statements = [];
  return {
    db,
    statements,
    DB: {
      prepare: (sql) => ({
        bind: (...values) => ({
          run: async () => {
            statements.push({ sql, values });
            return { meta: { changes: Number(db.prepare(sql).run(...values).changes) } };
          },
        }),
      }),
    },
    LIMIT: { limit: async () => ({ success: true }) },
  };
}

/** [count] made-up reports put in the table as if they came in [ago] ("-1 hours"). */
function fill(e, count, ago) {
  e.db.prepare(
    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) INSERT INTO reports (version, at) SELECT 'made-up', datetime('now', ?) FROM n"
  ).run(count, ago);
}

const rows = (e) => Number(e.db.prepare("SELECT COUNT(*) AS n FROM reports").get().n);

test("past 500 reports in a day, whoever sends them, nothing more is kept that day", async () => {
  const e = realEnv();
  fill(e, 499, "-23 hours");
  assert.equal((await worker.fetch(post(song, { headers: { "CF-Connecting-IP": "2001:db8:1::1" } }), e)).status, 204);
  assert.equal((await worker.fetch(post(song, { headers: { "CF-Connecting-IP": "2001:db8:2::1" } }), e)).status, 429);
  // Even one the old address counted and passed on.
  const passedOn = { ...e, FORWARD_KEY: "made-up-key" };
  assert.equal((await worker.fetch(post(song, { headers: { "X-Crosstune-Passed-On": "made-up-key" } }), passedOn)).status, 429);
  assert.equal(rows(e), 500);
});

test("reports from over a day ago don't count against the day's", async () => {
  const e = realEnv();
  fill(e, 600, "-25 hours");
  assert.equal((await worker.fetch(post(song), e)).status, 204);
  assert.equal(rows(e), 601);
});

test("the table never holds more than 9000 reports, and the daily clear-out makes room again", async () => {
  const e = realEnv();
  fill(e, 4000, "-200 days");
  fill(e, 4999, "-30 days");
  assert.equal((await worker.fetch(post(song), e)).status, 204);
  assert.equal((await worker.fetch(post(song), e)).status, 429);
  assert.equal(rows(e), 9000);
  await worker.scheduled({}, e);
  assert.equal(rows(e), 5000);
  assert.equal((await worker.fetch(post(song), e)).status, 204);
});

test("a report is kept whole in the real table, with what it says", async () => {
  const e = realEnv();
  assert.equal((await worker.fetch(post(reading), e)).status, 204);
  const kept = e.db.prepare("SELECT version, title, problem, lyric_line, word, right_reading FROM reports").all();
  assert.deepEqual(kept.map((it) => ({ ...it })), [{ version: "2.25.0", title: "Demo", problem: "wrong_reading", lyric_line: "空の花 again", word: "花", right_reading: "はな" }]);
});

test("the caps are looked up, never counted row by row, so a full table costs a flood nothing", async () => {
  const e = realEnv();
  await worker.fetch(post(song), e);
  const { sql, values } = e.statements[0];
  const plan = e.db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...values).map((it) => it.detail);
  assert.ok(plan.length > 0);
  // SCAN reads every row; SEARCH goes straight to the rows it wants, here one at each end.
  assert.deepEqual(plan.filter((it) => /\bSCAN\b/.test(it) && !/CONSTANT ROW/.test(it)), [], plan.join("\n"));
  assert.ok(plan.some((it) => /USING (COVERING )?INDEX reports_at\b/.test(it)), plan.join("\n"));
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
