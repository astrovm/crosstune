// Run with: node --test "report/*.test.mjs"
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import forward from "./forward.js";

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

/** The limit and the key, and what was passed on to where reports are kept now. */
function setUp({ allowed = true, answer = 204 } = {}) {
  const passed = [];
  const keys = [];
  globalThis.fetch = async (url, init) => (passed.push({ url, ...init }), new Response(null, { status: answer }));
  return { passed, keys, env: { FORWARD_KEY: "made-up-key", LIMIT: { limit: async ({ key }) => (keys.push(key), { success: allowed }) } } };
}

/** A report from [headers]'s sender, on the carrier Cloudflare names [asn], as Cloudflare puts it in request.cf. */
function post(body, headers = {}, asn = 64500) {
  const request = new Request("https://report.crosstune.4st.li/reports", { method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7", ...headers }, body });
  return Object.defineProperty(request, "cf", { value: asn === null ? {} : { asn } });
}

test("a report is counted against its sender and passed on with the key and the sender, for its network's day", async () => {
  const s = setUp();
  const response = await forward.fetch(post('{"version":"2.23.0"}', { "Content-Type": "application/json" }), s.env);
  assert.equal(response.status, 204);
  assert.deepEqual(s.keys, ["203.0.113.7"]);
  assert.equal(s.passed.length, 1);
  assert.equal(s.passed[0].url, "https://report.crosstune.cc/reports");
  assert.equal(s.passed[0].method, "POST");
  assert.equal(s.passed[0].body, '{"version":"2.23.0"}');
  assert.deepEqual(s.passed[0].headers, { "Content-Type": "application/json", "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": "203.0.113.7", "X-Crosstune-Carrier": "64500" });
  // The sender's address goes in that header only, never in the report.
  assert.ok(!s.passed[0].body.includes("203.0.113.7"));
});

test("what the new address answers is what the app is told", async () => {
  const s = setUp({ answer: 400 });
  assert.equal((await forward.fetch(post("nonsense"), s.env)).status, 400);
});

test("past the limit, nothing is passed on", async () => {
  const s = setUp({ allowed: false });
  assert.equal((await forward.fetch(post("{}"), s.env)).status, 429);
  assert.equal(s.passed.length, 0);
});

test("a report said to be far too long isn't read", async () => {
  const s = setUp();
  assert.equal((await forward.fetch(post("{}", { "Content-Length": String(64 * 1024 + 1) }), s.env)).status, 413);
  assert.equal(s.passed.length, 0);
  assert.deepEqual(s.keys, []);
});

test("anything but a report is passed on uncounted, and without the key", async () => {
  const s = setUp({ answer: 405 });
  const response = await forward.fetch(new Request("https://report.crosstune.4st.li/reports"), s.env);
  assert.equal(response.status, 405);
  assert.deepEqual(s.keys, []);
  assert.deepEqual(s.passed, [{ url: "https://report.crosstune.cc/reports", method: "GET" }]);
});

test("an IPv6 sender is counted by its /64 here too", async () => {
  const s = setUp();
  for (const address of ["2001:db8::1", "2001:db8::2"]) await forward.fetch(post("{}", { "CF-Connecting-IP": address }), s.env);
  assert.deepEqual(s.keys, ["2001:db8:0:0::/64", "2001:db8:0:0::/64"]);
});

test("a report's type is passed on as it was sent, and none is made up, so the new address judges it", async () => {
  const s = setUp();
  await forward.fetch(post("{}", { "Content-Type": "text/plain" }), s.env);
  // Cloudflare names no carrier for some addresses: none is made up.
  await forward.fetch(Object.defineProperty(new Request("https://report.crosstune.4st.li/reports", { method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7" }, body: new TextEncoder().encode("{}") }), "cf", { value: {} }), s.env);
  assert.deepEqual(s.passed.map((it) => it.headers), [
    { "Content-Type": "text/plain", "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": "203.0.113.7", "X-Crosstune-Carrier": "64500" },
    { "X-Crosstune-Passed-On": "made-up-key", "X-Crosstune-Sender": "203.0.113.7" },
  ]);
});
