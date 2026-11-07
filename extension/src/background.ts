// Two jobs. Keeps what each music tab says it plays, for the lyrics panel to read. And catches a top-level navigation to a music link, finds the same item in the service the user
// prefers, and sends the tab there. It cannot cancel the navigation (a page can't be held while
// the services are asked), so it lets it start and replaces it; when anything goes wrong it does
// nothing, and the original link opens as it would have without the extension.

import { decide } from "./destination.ts";
import { realFetcher } from "./http.ts";
import { PLAYER_MATCHES, WATCHED_HOSTS } from "./hosts.ts";
import { serviceFor } from "./services.ts";
import { loadSettings } from "./settings.ts";
import { SONG_MESSAGE, tabKey, type SongMessage } from "./song.ts";

/** The whole job has this long, so a slow service never leaves a link half done. */
const DEADLINE_MS = 8_000;

/** The latest navigation of each tab, so an answer that comes late for an older one is dropped. */
const latest = new Map<number, number>();
let counter = 0;

/** The link each tab was last sent away from. Going Back lands on it again, and must stay there. */
const sentFrom = new Map<number, string>();

/**
 * Each tab's last navigation, and when. Firefox tells of a navigation to another site twice, once
 * more as it moves it to that site's own process; the second is the same one, and is let be.
 */
const lastSeen = new Map<number, { url: string; at: number }>();
const SAME_NAVIGATION_MS = 1_000;

function country(): string {
  const region = navigator.language.split("-")[1] ?? "";
  return region.length === 2 ? region.toUpperCase() : "";
}

async function onNavigation(details: chrome.webNavigation.WebNavigationBaseCallbackDetails): Promise<void> {
  if (details.frameId !== 0) return;
  const { tabId, url } = details;
  const seen = lastSeen.get(tabId);
  lastSeen.set(tabId, { url, at: details.timeStamp });
  if (seen !== undefined && seen.url === url && details.timeStamp - seen.at < SAME_NAVIGATION_MS) return;
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
  lastSeen.delete(tabId);
  void chrome.storage.session.remove(tabKey(tabId));
});

// What a music tab says it plays, kept for the session, each tab under a key of its own.
chrome.runtime.onMessage.addListener((message: Partial<SongMessage> | undefined, sender) => {
  const tabId = sender.tab?.id;
  if (message?.type !== SONG_MESSAGE || sender.id !== chrome.runtime.id || tabId === undefined) return;
  if (message.song === null || message.song === undefined) void chrome.storage.session.remove(tabKey(tabId));
  else void chrome.storage.session.set({ [tabKey(tabId)]: { ...message.song, tabId, at: Date.now() } });
});

// A tab that goes to a new page has stopped playing what it was; if it's a music page, it says so again.
chrome.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId === 0) void chrome.storage.session.remove(tabKey(details.tabId));
});

/** What only Firefox has: its sidebar, the panel's place there. */
const firefoxSidebar = (globalThis as { browser?: { sidebarAction?: { toggle(): Promise<void> } } }).browser?.sidebarAction;

// The toolbar button opens the lyrics. The settings are one button further, in the panel.
if (firefoxSidebar !== undefined) {
  chrome.action.onClicked.addListener(() => void firefoxSidebar.toggle());
} else {
  void chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
  // Chrome doesn't give a tab already open the content script when the extension arrives.
  chrome.runtime.onInstalled.addListener(() => {
    void chrome.tabs.query({ url: PLAYER_MATCHES }).then((tabs) => {
      for (const tab of tabs) {
        if (tab.id !== undefined) void chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] }).catch(() => {});
      }
    });
  });
}
