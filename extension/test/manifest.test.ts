import assert from "node:assert/strict";
import { test } from "node:test";
import { LYRICS_HOSTS, PLAYER_HOSTS, WATCHED_HOSTS } from "../src/hosts.ts";
import { manifestFor } from "../src/manifest.ts";

const chrome = manifestFor("chrome", "1.2.3");
const firefox = manifestFor("firefox", "1.2.3");

test("Chrome gets a side panel and Firefox a sidebar, each its own", () => {
  assert.deepEqual(chrome["side_panel"], { default_path: "panel.html" });
  assert.equal(chrome["sidebar_action"], undefined);
  assert.equal((firefox["sidebar_action"] as { default_panel: string }).default_panel, "panel.html");
  assert.equal((firefox["sidebar_action"] as { open_at_install: boolean }).open_at_install, false);
  assert.equal(firefox["side_panel"], undefined);
});

test("the permissions are the narrow ones, each with a reason in the store's README", () => {
  assert.deepEqual(chrome["permissions"], ["webNavigation", "storage", "declarativeNetRequestWithHostAccess", "sidePanel", "scripting"]);
  assert.deepEqual(firefox["permissions"], ["webNavigation", "storage", "declarativeNetRequestWithHostAccess"]);
});

test("no permission for every site, or to read every tab", () => {
  for (const manifest of [chrome, firefox]) {
    assert.ok(!(manifest["permissions"] as string[]).includes("tabs"));
    assert.ok(!(manifest["permissions"] as string[]).includes("activeTab"));
    for (const host of manifest["host_permissions"] as string[]) assert.doesNotMatch(host, /<all_urls>|^\*|^https:\/\/\*\/|^https:\/\/\*\.[a-z]+\/\*$/);
  }
});

test("the lyrics add only the two sites that won't answer otherwise", () => {
  const hosts = chrome["host_permissions"] as string[];
  for (const host of LYRICS_HOSTS) assert.ok(hosts.includes(`https://${host}/*`));
  assert.ok(!hosts.some((host) => host.includes("lrclib") || host.includes("sscdn")));
});

test("the content script runs only on music sites the extension already reads", () => {
  const watched: readonly string[] = WATCHED_HOSTS;
  for (const host of PLAYER_HOSTS) assert.ok(watched.includes(host), host);
  const [script] = chrome["content_scripts"] as { matches: string[] }[];
  assert.deepEqual(script?.matches, PLAYER_HOSTS.map((host) => `https://${host}/*`));
});
