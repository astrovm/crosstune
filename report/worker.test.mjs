// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
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
      prepare: (sql) => ({ bind: (...values) => ({ run: async () => saved.push({ sql, values }) }) }),
    },
    LIMIT: { limit: async ({ key }) => (keys.push(key), { success: allowed }) },
  };
}

function post(body, { path = "/reports", method = "POST", headers = {} } = {}) {
  return new Request(`https://report.crosstune.4st.li${path}`, {
    method,
    headers: { "CF-Connecting-IP": "203.0.113.7", ...headers },
    body: method === "GET" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
}

const song = {
  version: "2.12.0",
  android: "15 (35)",
  comment: "  The chorus comes late  ",
  song: {
    title: "Demo", artist: "Band", app: "YouTube Music", timed: true, synced: true, sung: false, lengthMs: 220937,
    timing: [{ line: "Take me I'm yours", timedMs: 34210, heardMs: 32900 }],
  },
};

test("a report is kept, with only what a report has", async () => {
  const e = env();
  const response = await worker.fetch(post({ ...song, ip: "198.51.100.1", extra: "dropped" }), e);
  assert.equal(response.status, 204);
  assert.equal(e.saved.length, 1);
  assert.deepEqual(e.saved[0].values, [
    "2.12.0", "15 (35)", "Demo", "Band", "YouTube Music", 1, 1, 0, 220937,
    JSON.stringify([{ line: "Take me I'm yours", timedMs: 34210, heardMs: 32900 }]), "The chorus comes late", null,
  ]);
  // The address only counts against the limit.
  assert.deepEqual(e.keys, ["203.0.113.7"]);
  assert.ok(!JSON.stringify(e.saved).includes("203.0.113.7"));
});

test("a report about the app, with no song, is kept too", async () => {
  const e = env();
  assert.equal((await worker.fetch(post({ version: "2.12.0", comment: "Hi" }), e)).status, 204);
  assert.deepEqual(e.saved[0].values, ["2.12.0", null, null, null, null, null, null, null, null, null, "Hi", null]);
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
  const big = { ...song, comment: "x".repeat(40 * 1024) };
  assert.equal((await worker.fetch(post(big), e)).status, 413);
  // Even when it says it's small.
  assert.equal((await worker.fetch(post(big, { headers: { "Content-Length": "10" } }), e)).status, 413);
  assert.equal(e.saved.length, 0);
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
  assert.deepEqual(e.saved.map((it) => it.values.at(-1)), ["no_lyrics", null]);
});
