// A fake internet for the tests: each request is answered from a table, with the answers the
// services really gave (trimmed, in fixtures/). A request nobody planned for fails the test.

import { readFileSync } from "node:fs";
import { Unreachable, type Fetcher, type Init, type Reply } from "../src/http.ts";

export function fixture(name: string): string {
  return readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8");
}

export type Answer = string | { status?: number; url?: string; text?: string } | "unreachable";

type Route = [match: string | RegExp, answer: Answer | ((url: string, init: Init | undefined) => Answer)];

export interface Internet {
  fetch: Fetcher;
  /** Every URL asked, in order. */
  asked: string[];
}

export function internet(routes: Route[]): Internet {
  const asked: string[] = [];
  const fetch: Fetcher = async (url, init) => {
    asked.push(url);
    const route = routes.find(([match]) => (typeof match === "string" ? url.startsWith(match) : match.test(url)));
    if (route === undefined) throw new Error(`Unplanned request: ${url}`);
    const answer = typeof route[1] === "function" ? route[1](url, init) : route[1];
    if (answer === "unreachable") throw new Unreachable("offline");
    const given = typeof answer === "string" ? { text: answer } : answer;
    const status = given.status ?? 200;
    const reply: Reply = { status, ok: status >= 200 && status < 300, url: given.url ?? url, text: given.text ?? "" };
    return reply;
  };
  return { fetch, asked };
}

/** A page whose only content is what a service tells a link preview. */
/** A Spotify embed page, which is where the extension reads what a Spotify link is. */
export function spotifyEmbed(name: string, artist: string, durationMs?: number): string {
  const entity = { type: "track", name, artists: [{ name: artist }], ...(durationMs === undefined ? {} : { duration: durationMs }) };
  const data = JSON.stringify({ props: { pageProps: { state: { data: { entity } } } } });
  return `<!doctype html><html><body><script id="__NEXT_DATA__" type="application/json">${data}</script></body></html>`;
}
