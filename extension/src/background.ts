// Catches a top-level navigation to a music link, finds the same item in the service the user
// prefers, and sends the tab there. It cannot cancel the navigation (a page can't be held while
// the services are asked), so it lets it start and replaces it; when anything goes wrong it does
// nothing, and the original link opens as it would have without the extension.

import { decide } from "./destination.ts";
import { realFetcher } from "./http.ts";
import { WATCHED_HOSTS } from "./hosts.ts";
import { serviceFor } from "./services.ts";
import { loadSettings } from "./settings.ts";

/** The whole job has this long, so a slow service never leaves a link half done. */
const DEADLINE_MS = 8_000;

/** The latest navigation of each tab, so an answer that comes late for an older one is dropped. */
const latest = new Map<number, number>();
let counter = 0;

/** The link each tab was last sent away from. Going Back lands on it again, and must stay there. */
const sentFrom = new Map<number, string>();

function country(): string {
  const region = navigator.language.split("-")[1] ?? "";
  return region.length === 2 ? region.toUpperCase() : "";
}

async function onNavigation(details: chrome.webNavigation.WebNavigationBaseCallbackDetails): Promise<void> {
  if (details.frameId !== 0) return;
  const { tabId, url } = details;
  if (sentFrom.get(tabId) === url) {
    sentFrom.delete(tabId);
    return;
  }
  const settings = await loadSettings();
  if (!settings.enabled) return;
  const mine = ++counter;
  latest.set(tabId, mine);

  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), DEADLINE_MS);
  try {
    const decision = await decide(url, settings.service, { fetch: realFetcher(5_000, abort.signal), country: country() });
    if (decision.kind !== "go" || latest.get(tabId) !== mine) return;
    // The tab may have gone elsewhere since; only a music page is ours to replace.
    const tab = await chrome.tabs.get(tabId);
    const now = tab.pendingUrl ?? tab.url;
    if (now !== undefined && serviceFor(now) === null) return;
    sentFrom.set(tabId, url);
    await chrome.tabs.update(tabId, { url: decision.url });
  } catch {
    // The tab is gone or the browser refused; the link stays as it was.
  } finally {
    clearTimeout(timer);
    if (latest.get(tabId) === mine) latest.delete(tabId);
  }
}

chrome.webNavigation.onBeforeNavigate.addListener((details) => void onNavigation(details), {
  url: WATCHED_HOSTS.map((hostEquals) => ({ hostEquals })),
});

// Spotify's short links live on a subdomain of their own for each sender.
chrome.webNavigation.onBeforeNavigate.addListener((details) => void onNavigation(details), { url: [{ hostSuffix: ".spotify.link" }] });

chrome.tabs.onRemoved.addListener((tabId) => {
  latest.delete(tabId);
  sentFrom.delete(tabId);
});

chrome.action.onClicked.addListener(() => void chrome.runtime.openOptionsPage());
