// Sends the packaged extension to each store whose keys are set, and skips the others: the Chrome
// Web Store, Firefox Add-ons (with its listing text and the source it asks for) and Edge Add-ons.
// Each store's first version, and its listing, are made by hand in its dashboard; this sends the
// versions after it. The release workflow runs it when the version in package.json changes.
//   npm run package && node scripts/publish.ts
//
// Chrome:  CHROME_CLIENT_ID, CHROME_CLIENT_SECRET, CHROME_REFRESH_TOKEN, CHROME_PUBLISHER_ID, CHROME_EXTENSION_ID
// Firefox: AMO_API_KEY, AMO_API_SECRET
// Edge:    EDGE_CLIENT_ID, EDGE_API_KEY, EDGE_PRODUCT_ID

import { createHmac, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

type Env = Record<string, string | undefined>;
type Fetch = (url: string, init?: { method?: string; headers?: Record<string, string>; body?: BodyInit }) => Promise<Response>;
type Sleep = (ms: number) => Promise<void>;
export type Store = "chrome" | "firefox" | "edge";
interface Texts { summary: string; description: string }

export const STORES: Record<Store, string[]> = {
  chrome: ["CHROME_CLIENT_ID", "CHROME_CLIENT_SECRET", "CHROME_REFRESH_TOKEN", "CHROME_PUBLISHER_ID", "CHROME_EXTENSION_ID"],
  firefox: ["AMO_API_KEY", "AMO_API_SECRET"],
  edge: ["EDGE_CLIENT_ID", "EDGE_API_KEY", "EDGE_PRODUCT_ID"],
};

/** The store's keys that aren't set; none missing means it's sent. */
export function missing(store: Store, env: Env): string[] {
  return STORES[store].filter((key) => !env[key]);
}

/** How Firefox Add-ons names each of the extension's languages. */
export const AMO_LOCALES: Record<string, string> = {
  en: "en-US", de: "de", es: "es-AR", fr: "fr", hi: "hi-IN", id: "id", it: "it", ja: "ja", ko: "ko",
  nl: "nl", pl: "pl", pt_BR: "pt-BR", ru: "ru", tr: "tr", zh_CN: "zh-CN", zh_TW: "zh-TW",
};

/** [listing]'s texts, by Firefox Add-ons' names for their languages. */
export function amoListing(listing: Record<string, Texts>): Record<keyof Texts, Record<string, string>> {
  const field = (name: keyof Texts) => Object.fromEntries(Object.entries(listing).map(([locale, texts]) => [AMO_LOCALES[locale] ?? locale, texts[name]]));
  return { summary: field("summary"), description: field("description") };
}

const base64url = (data: string) => Buffer.from(data).toString("base64url");

/** The token Firefox Add-ons asks of each request, good for a minute. */
export function amoToken(key: string, secret: string, now = Date.now(), nonce: string = randomUUID()): string {
  const iat = Math.floor(now / 1000);
  const unsigned = `${base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }))}.${base64url(JSON.stringify({ iss: key, jti: nonce, iat, exp: iat + 60 }))}`;
  return `${unsigned}.${createHmac("sha256", secret).update(unsigned).digest("base64url")}`;
}

async function checked(response: Response, what: string): Promise<string> {
  const text = await response.text();
  if (!response.ok) throw new Error(`${what}: ${response.status} ${text.slice(0, 500)}`);
  return text;
}

// The stores' answers, read field by field where they matter.
async function json(response: Response, what: string): Promise<Record<string, unknown>> {
  const text = await checked(response, what);
  return text === "" ? {} : JSON.parse(text);
}

/** Asks [check] until it says it's done, a few seconds apart. */
async function until<T>(check: () => Promise<T | undefined>, sleep: Sleep, tries = 60, waitMs = 5_000): Promise<T> {
  for (let tried = 0; tried < tries; tried++) {
    const done = await check();
    if (done !== undefined) return done;
    await sleep(waitMs);
  }
  throw new Error("Gave up waiting");
}

export async function toChrome({ env, zip, fetch }: { env: Env; zip: string; fetch: Fetch }): Promise<string> {
  const token = await json(await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    body: new URLSearchParams({ client_id: env["CHROME_CLIENT_ID"] ?? "", client_secret: env["CHROME_CLIENT_SECRET"] ?? "", refresh_token: env["CHROME_REFRESH_TOKEN"] ?? "", grant_type: "refresh_token" }),
  }), "Chrome sign-in");
  const headers = { authorization: `Bearer ${String(token["access_token"])}` };
  const item = `publishers/${env["CHROME_PUBLISHER_ID"]}/items/${env["CHROME_EXTENSION_ID"]}`;
  const upload = await json(await fetch(`https://chromewebstore.googleapis.com/upload/v2/${item}:upload`, {
    method: "POST", headers: { ...headers, "content-type": "application/zip" }, body: await readFile(zip),
  }), "Chrome upload");
  if (upload["uploadState"] === "FAILED") throw new Error(`Chrome upload: ${JSON.stringify(upload)}`);
  await json(await fetch(`https://chromewebstore.googleapis.com/v2/${item}:publish`, { method: "POST", headers }), "Chrome publish");
  return "sent for review";
}

export async function toFirefox({ env, zip, source, listing, guid, fetch, sleep }: {
  env: Env; zip: string; source: string; listing: Record<string, Texts>; guid: string; fetch: Fetch; sleep: Sleep;
}): Promise<string> {
  const api = "https://addons.mozilla.org/api/v5/addons";
  const auth = () => ({ authorization: `JWT ${amoToken(env["AMO_API_KEY"] ?? "", env["AMO_API_SECRET"] ?? "")}` });
  const addon = `${api}/addon/${encodeURIComponent(guid)}`;
  // The listing first, so the store's words match this version's.
  await json(await fetch(`${addon}/`, {
    method: "PATCH", headers: { ...auth(), "content-type": "application/json" }, body: JSON.stringify(amoListing(listing)),
  }), "Firefox listing");
  const form = new FormData();
  form.append("upload", new Blob([await readFile(zip)]), basename(zip));
  form.append("channel", "listed");
  const uuid = String((await json(await fetch(`${api}/upload/`, { method: "POST", headers: auth(), body: form }), "Firefox upload"))["uuid"]);
  const checkedUpload = await until(async () => {
    const state = await json(await fetch(`${api}/upload/${uuid}/`, { headers: auth() }), "Firefox validation");
    return state["processed"] === true ? state : undefined;
  }, sleep);
  if (checkedUpload["valid"] !== true) throw new Error(`Firefox validation: ${JSON.stringify(checkedUpload["validation"]).slice(0, 1000)}`);
  // Firefox's reviewers build the bundled code again from its source.
  const submission = new FormData();
  submission.append("upload", uuid);
  submission.append("source", new Blob([await readFile(source)]), basename(source));
  submission.append("approval_notes", "Build with Node 24: npm ci && npm run build. The add-on is dist/firefox.");
  await json(await fetch(`${addon}/versions/`, { method: "POST", headers: auth(), body: submission }), "Firefox version");
  return "sent for review";
}

export async function toEdge({ env, zip, fetch, sleep }: { env: Env; zip: string; fetch: Fetch; sleep: Sleep }): Promise<string> {
  const api = `https://api.addons.microsoftedge.microsoft.com/v1/products/${env["EDGE_PRODUCT_ID"]}/submissions`;
  const headers = { authorization: `ApiKey ${env["EDGE_API_KEY"]}`, "x-clientid": env["EDGE_CLIENT_ID"] ?? "" };
  // Each step answers with where to ask how it went; its last part is the operation.
  const operation = async (response: Response, what: string): Promise<string> => {
    await checked(response, what);
    const location = response.headers.get("location") ?? "";
    return location.split("/").filter(Boolean).pop() ?? "";
  };
  const finished = (url: string, what: string) => until(async () => {
    const state = await json(await fetch(url, { headers }), what);
    if (state["status"] === "InProgress") return undefined;
    if (state["status"] !== "Succeeded") throw new Error(`${what}: ${JSON.stringify(state).slice(0, 1000)}`);
    return state;
  }, sleep);
  const uploaded = await operation(await fetch(`${api}/draft/package`, {
    method: "POST", headers: { ...headers, "content-type": "application/zip" }, body: await readFile(zip),
  }), "Edge upload");
  await finished(`${api}/draft/package/operations/${uploaded}`, "Edge upload");
  const published = await operation(await fetch(api, {
    method: "POST", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ notes: "Built from github.com/astrovm/crosstune/tree/main/extension." }),
  }), "Edge publish");
  await finished(`${api}/operations/${published}`, "Edge publish");
  return "sent for review";
}

/** Sends to each store with its keys; one failing still lets the others go, then fails the run. */
export async function publish({ env, dist, fetch, sleep, log }: { env: Env; dist: string; fetch: Fetch; sleep: Sleep; log: (line: string) => void }): Promise<boolean> {
  const pkg = JSON.parse(await readFile(join(dist, "..", "package.json"), "utf8"));
  const manifest = JSON.parse(await readFile(join(dist, "firefox", "manifest.json"), "utf8"));
  const version = pkg.version;
  const file = (name: string) => join(dist, `crosstune-${version}-${name}.zip`);
  const listing = JSON.parse(await readFile(join(dist, "..", "store", "listing.json"), "utf8"));
  const sends: Record<Store, () => Promise<string>> = {
    chrome: () => toChrome({ env, zip: file("chrome"), fetch }),
    firefox: () => toFirefox({ env, zip: file("firefox"), source: file("source"), listing, guid: manifest.browser_specific_settings.gecko.id, fetch, sleep }),
    edge: () => toEdge({ env, zip: file("chrome"), fetch, sleep }),
  };
  let failed = false;
  for (const store of Object.keys(sends) as Store[]) {
    const send = sends[store];
    const unset = missing(store, env);
    if (unset.length > 0) {
      log(`${store}: skipped, ${unset.join(", ")} not set`);
      continue;
    }
    try {
      log(`${store}: ${await send()}`);
    } catch (error) {
      failed = true;
      log(`${store}: failed, ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  return !failed;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dist = join(dirname(fileURLToPath(import.meta.url)), "..", "dist");
  const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
  const ok = await publish({ env: process.env, dist, fetch: (url, init) => fetch(url, init), sleep, log: console.log });
  process.exit(ok ? 0 : 1);
}
