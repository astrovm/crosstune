import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";
import type { Source } from "../src/lyrics.ts";

/**
 * How the stores and the docs name each site the lyrics panel sends a song's title and artist to. A new
 * [Source] needs its name here, or the types don't check, and then in every text below.
 */
const NAMES: Record<Source, readonly string[]> = {
  lrclib: ["LRCLIB"],
  youtube_music: ["YouTube Music"],
  netease: ["NetEase Cloud Music", "网易云音乐", "網易雲音樂"],
  letras: ["Letras"],
  genius: ["Genius"],
};

const repo = new URL("../../", import.meta.url);
const read = (path: string): string => readFileSync(new URL(path, repo), "utf8");

function namesEverySite(text: string, where: string): void {
  for (const [source, names] of Object.entries(NAMES)) {
    assert.ok(names.some((name) => text.includes(name)), `${where} doesn't name ${source}`);
  }
}

test("each store listing names every site the lyrics are looked for on", () => {
  const listing: Record<string, { description: string }> = JSON.parse(read("extension/store/listing.json"));
  assert.equal(Object.keys(listing).length, 16);
  for (const [locale, { description }] of Object.entries(listing)) namesEverySite(description, `listing.json's ${locale}`);
});

test("the stores' answers, README and every privacy page name them too", () => {
  namesEverySite(read("extension/store/README.md"), "extension/store/README.md");
  const readme = read("README.md");
  namesEverySite(readme.slice(readme.indexOf("The [browser extension]")), "README's Privacy");
  const strings = readdirSync(new URL("site/strings/", repo)).filter((name) => name.endsWith(".json"));
  assert.equal(strings.length, 16);
  for (const name of strings) {
    const body: [string, string][] = JSON.parse(read(`site/strings/${name}`))["privacy_body"];
    // The paragraph on the extension, the only one that names Brave, which no language changes.
    const extension = body.find(([, text]) => /\bBrave\b/.test(text));
    assert.ok(extension, `${name} has no paragraph on the extension`);
    namesEverySite(extension[1], `${name}'s privacy page`);
  }
});
