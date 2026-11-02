import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { AMO_LOCALES, amoListing, amoToken, publish } from "../scripts/publish.ts";

const CHROME = { CHROME_CLIENT_ID: "id", CHROME_CLIENT_SECRET: "secret", CHROME_REFRESH_TOKEN: "refresh", CHROME_PUBLISHER_ID: "pub", CHROME_EXTENSION_ID: "ext" };
const FIREFOX = { AMO_API_KEY: "user:1:2", AMO_API_SECRET: "amo-secret" };
const EDGE = { EDGE_CLIENT_ID: "client", EDGE_API_KEY: "key", EDGE_PRODUCT_ID: "product" };

interface Asked { method: string; url: string; headers: Record<string, string>; body: unknown }
type Answer = { status?: number; json?: unknown; location?: string };

/** A folder laid out as the extension's, with its packages built. */
async function built(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "crosstune-publish-"));
  await mkdir(join(root, "dist", "firefox"), { recursive: true });
  await mkdir(join(root, "store"));
  await writeFile(join(root, "package.json"), JSON.stringify({ version: "1.2.3" }));
  await writeFile(join(root, "store", "listing.json"), JSON.stringify({ en: { summary: "Opens links.", description: "Long." }, es: { summary: "Abre links.", description: "Largo." } }));
  await writeFile(join(root, "dist", "firefox", "manifest.json"), JSON.stringify({ browser_specific_settings: { gecko: { id: "extension@crosstune.4st.li" } } }));
  for (const name of ["chrome", "firefox", "source"]) await writeFile(join(root, "dist", `crosstune-1.2.3-${name}.zip`), name);
  return join(root, "dist");
}

/** A fetch that answers each request with the first of [answers] whose pattern its method and address match. */
function stores(answers: [RegExp, Answer | Answer[]][]) {
  const asked: Asked[] = [];
  const fetch = async (url: string, init: { method?: string; headers?: Record<string, string>; body?: unknown } = {}) => {
    const method = init.method ?? "GET";
    asked.push({ method, url, headers: init.headers ?? {}, body: init.body });
    const found = answers.find(([pattern]) => pattern.test(`${method} ${url}`));
    if (found === undefined) return new Response("not here", { status: 404 });
    const listed = found[1];
    const answer = Array.isArray(listed) ? (listed.length > 1 ? (listed.shift() ?? {}) : nth(listed, 0)) : listed;
    const headers = answer.location === undefined ? undefined : { location: answer.location };
    return new Response(answer.json === undefined ? "" : JSON.stringify(answer.json), { status: answer.status ?? 200, headers });
  };
  return { asked, fetch };
}

/** The [index]th of [items], which the test expects to be there. */
function nth<T>(items: T[], index: number): T {
  const item = items[index];
  if (item === undefined) throw new Error(`Nothing at ${index} of ${items.length}`);
  return item;
}

async function run(env: Record<string, string>, answers: [RegExp, Answer | Answer[]][]) {
  const { asked, fetch } = stores(answers);
  const said: string[] = [];
  const ok = await publish({ env, dist: await built(), fetch, sleep: async () => {}, log: (line: string) => said.push(line) });
  return { ok, asked, said };
}

const CHROME_ANSWERS: [RegExp, Answer][] = [
  [/^POST https:\/\/oauth2\.googleapis\.com\/token$/, { json: { access_token: "token" } }],
  [/^POST .*\/upload\/v2\/publishers\/pub\/items\/ext:upload$/, { json: { uploadState: "SUCCEEDED" } }],
  [/^POST .*\/v2\/publishers\/pub\/items\/ext:publish$/, { json: { state: "PENDING_REVIEW" } }],
];

test("a store without its keys is skipped, and nothing is asked of it", async () => {
  const { ok, asked, said } = await run({ CHROME_CLIENT_ID: "id", EDGE_CLIENT_ID: "client", EDGE_API_KEY: "key" }, []);
  assert.equal(ok, true);
  assert.deepEqual(asked, []);
  assert.deepEqual(said, [
    "chrome: skipped, CHROME_CLIENT_SECRET, CHROME_REFRESH_TOKEN, CHROME_PUBLISHER_ID, CHROME_EXTENSION_ID not set",
    "firefox: skipped, AMO_API_KEY, AMO_API_SECRET not set",
    "edge: skipped, EDGE_PRODUCT_ID not set",
  ]);
});

test("Chrome gets a token from its refresh token, then the Chrome zip, then is told to publish", async () => {
  const { ok, asked, said } = await run(CHROME, CHROME_ANSWERS);
  assert.equal(ok, true);
  assert.deepEqual(asked.map((a) => a.method + " " + a.url), [
    "POST https://oauth2.googleapis.com/token",
    "POST https://chromewebstore.googleapis.com/upload/v2/publishers/pub/items/ext:upload",
    "POST https://chromewebstore.googleapis.com/v2/publishers/pub/items/ext:publish",
  ]);
  assert.equal(String(nth(asked, 0).body), "client_id=id&client_secret=secret&refresh_token=refresh&grant_type=refresh_token");
  assert.equal(nth(asked, 1).headers["authorization"], "Bearer token");
  assert.equal(Buffer.from(nth(asked, 1).body as Uint8Array).toString(), "chrome");
  assert.equal(said[0], "chrome: sent for review");
});

test("a Chrome upload it turns away isn't published", async () => {
  const { ok, asked, said } = await run(CHROME, [nth(CHROME_ANSWERS, 0), [/:upload$/, { json: { uploadState: "FAILED" } }]]);
  assert.equal(ok, false);
  assert.equal(asked.length, 2);
  assert.match(nth(said, 0), /^chrome: failed, Chrome upload: .*FAILED/);
});

const FIREFOX_ADDON = "https://addons.mozilla.org/api/v5/addons/addon/extension%40crosstune.4st.li";

test("Firefox gets its listing in its own language names, the zip, and once it's checked, the version with its source", async () => {
  const { ok, asked, said } = await run(FIREFOX, [
    [/^PATCH .*\/addon\/extension%40crosstune\.4st\.li\/$/, { json: {} }],
    [/^POST .*\/addons\/upload\/$/, { json: { uuid: "u1" } }],
    [/^GET .*\/addons\/upload\/u1\/$/, [{ json: { processed: false } }, { json: { processed: true, valid: true } }]],
    [/^POST .*\/versions\/$/, { status: 201, json: { id: 7 } }],
  ]);
  assert.equal(ok, true, said.join("\n"));
  assert.deepEqual(asked.map((a) => a.method + " " + a.url.replace(FIREFOX_ADDON, "ADDON")), [
    "PATCH ADDON/",
    "POST https://addons.mozilla.org/api/v5/addons/upload/",
    "GET https://addons.mozilla.org/api/v5/addons/upload/u1/",
    "GET https://addons.mozilla.org/api/v5/addons/upload/u1/",
    "POST ADDON/versions/",
  ]);
  assert.deepEqual(JSON.parse(nth(asked, 0).body as string), { summary: { "en-US": "Opens links.", "es-AR": "Abre links." }, description: { "en-US": "Long.", "es-AR": "Largo." } });
  const upload = nth(asked, 1).body as FormData;
  assert.equal(upload.get("channel"), "listed");
  assert.equal(await (upload.get("upload") as File).text(), "firefox");
  const version = nth(asked, 4).body as FormData;
  assert.equal(version.get("upload"), "u1");
  assert.equal(await (version.get("source") as File).text(), "source");
  for (const request of asked) assert.match(request.headers["authorization"] ?? "", /^JWT [\w-]+\.[\w-]+\.[\w-]+$/);
});

test("a Firefox zip that fails its checks isn't submitted", async () => {
  const { ok, asked, said } = await run(FIREFOX, [
    [/^PATCH /, { json: {} }],
    [/^POST .*\/addons\/upload\/$/, { json: { uuid: "u1" } }],
    [/^GET .*\/upload\/u1\/$/, { json: { processed: true, valid: false, validation: { errors: 1 } } }],
  ]);
  assert.equal(ok, false);
  assert.equal(asked.filter((a) => a.url.endsWith("/versions/")).length, 0);
  assert.match(nth(said, 1), /^firefox: failed, Firefox validation: \{"errors":1\}/);
});

test("Edge gets the Chrome zip, and is told to publish once it's taken it", async () => {
  const api = "https://api.addons.microsoftedge.microsoft.com/v1/products/product/submissions";
  const { ok, asked, said } = await run(EDGE, [
    [/^POST .*\/draft\/package$/, { status: 202, location: "op-upload" }],
    [/^GET .*\/draft\/package\/operations\/op-upload$/, [{ json: { status: "InProgress" } }, { json: { status: "Succeeded" } }]],
    [/^POST .*\/submissions$/, { status: 202, location: "/v1/products/product/submissions/operations/op-publish" }],
    [/^GET .*\/submissions\/operations\/op-publish$/, { json: { status: "Succeeded" } }],
  ]);
  assert.equal(ok, true, said.join("\n"));
  assert.deepEqual(asked.map((a) => a.method + " " + a.url.replace(api, "API")), [
    "POST API/draft/package",
    "GET API/draft/package/operations/op-upload",
    "GET API/draft/package/operations/op-upload",
    "POST API",
    "GET API/operations/op-publish",
  ]);
  assert.equal(nth(asked, 0).headers["authorization"], "ApiKey key");
  assert.equal(nth(asked, 0).headers["x-clientid"], "client");
  assert.equal(Buffer.from(nth(asked, 0).body as Uint8Array).toString(), "chrome");
});

test("an Edge package it turns away isn't published", async () => {
  const { ok, asked, said } = await run(EDGE, [
    [/^POST .*\/draft\/package$/, { status: 202, location: "op" }],
    [/^GET .*\/operations\/op$/, { json: { status: "Failed", message: "Bad manifest" } }],
  ]);
  assert.equal(ok, false);
  assert.equal(asked.length, 2);
  assert.match(nth(said, 2), /^edge: failed, Edge upload: .*Bad manifest/);
});

test("one store failing still lets the next go, and fails the run", async () => {
  const { ok, said } = await run({ ...CHROME, ...EDGE }, [
    [/oauth2/, { status: 401, json: { error: "invalid_grant" } }],
    [/^POST .*\/draft\/package$/, { status: 202, location: "op" }],
    [/^GET /, { json: { status: "Succeeded" } }],
    [/^POST .*\/submissions$/, { status: 202, location: "op2" }],
  ]);
  assert.equal(ok, false);
  assert.match(nth(said, 0), /^chrome: failed, Chrome sign-in: 401/);
  assert.equal(said[2], "edge: sent for review");
});

test("Firefox's token is signed with the secret and lasts a minute", () => {
  const token = amoToken("user:1:2", "amo-secret", 1_700_000_000_000, "nonce");
  const [header = "", claims = "", signature] = token.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(header, "base64url").toString()), { alg: "HS256", typ: "JWT" });
  assert.deepEqual(JSON.parse(Buffer.from(claims, "base64url").toString()), { iss: "user:1:2", jti: "nonce", iat: 1_700_000_000, exp: 1_700_000_060 });
  assert.equal(signature, createHmac("sha256", "amo-secret").update(`${header}.${claims}`).digest("base64url"));
});

test("every language of the extension has a Firefox Add-ons name", async () => {
  const { readdirSync } = await import("node:fs");
  assert.deepEqual(Object.keys(AMO_LOCALES).sort(), readdirSync(new URL("../_locales/", import.meta.url)).sort());
  assert.deepEqual(amoListing({ zh_TW: { summary: "s", description: "d" } }), { summary: { "zh-TW": "s" }, description: { "zh-TW": "d" } });
});
