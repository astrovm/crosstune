// What the user chose, kept on this device only (not synced: nothing about listening leaves it).

import { isService, type Service } from "./services.ts";

export interface Settings {
  enabled: boolean;
  service: Service;
}

/** YouTube Music is the app's default too. */
export const DEFAULTS: Settings = { enabled: true, service: "youtubemusic" };

export async function loadSettings(): Promise<Settings> {
  const stored = await chrome.storage.local.get({ ...DEFAULTS });
  return {
    enabled: stored["enabled"] !== false,
    service: isService(stored["service"]) ? stored["service"] : DEFAULTS.service,
  };
}

export async function saveSettings(change: Partial<Settings>): Promise<void> {
  await chrome.storage.local.set(change);
}
