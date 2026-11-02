// The options page: on or off, and the service links open in.

import { HOST_PERMISSIONS } from "./hosts.ts";
import { SERVICES, SERVICE_NAMES, isService } from "./services.ts";
import { loadSettings, saveSettings } from "./settings.ts";

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (found === null) throw new Error(`Missing #${id}`);
  return found as T;
}

for (const node of document.querySelectorAll<HTMLElement>("[data-i18n]")) {
  const text = chrome.i18n.getMessage(node.dataset["i18n"] ?? "");
  if (text !== "") node.textContent = text;
}

const enabled = element<HTMLInputElement>("enabled");
const service = element<HTMLSelectElement>("service");
const access = element<HTMLElement>("access");

for (const key of SERVICES) {
  const option = document.createElement("option");
  option.value = key;
  option.textContent = SERVICE_NAMES[key];
  service.append(option);
}

async function main(): Promise<void> {
  const settings = await loadSettings();
  enabled.checked = settings.enabled;
  service.value = settings.service;

  enabled.addEventListener("change", () => void saveSettings({ enabled: enabled.checked }));
  service.addEventListener("change", () => {
    if (isService(service.value)) void saveSettings({ service: service.value });
  });

  // Chrome grants the sites when the extension is installed; Firefox asks for them after.
  async function showAccess(): Promise<void> {
    access.hidden = await chrome.permissions.contains({ origins: HOST_PERMISSIONS });
  }
  element("allow").addEventListener("click", () => {
    void chrome.permissions.request({ origins: HOST_PERMISSIONS }).then(showAccess);
  });
  await showAccess();
}

void main();
