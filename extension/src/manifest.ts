// What the browser is told the extension is, for each browser. Chrome, Edge and Brave have a side
// panel; Firefox has a sidebar, shows the panel page there, and asks for nothing more to do it.

import { HOST_PERMISSIONS, PLAYER_MATCHES } from "./hosts.ts";

export type Target = "chrome" | "firefox";

const ICONS = Object.fromEntries([16, 32, 48, 128].map((size) => [size, `icons/icon-${size}.png`]));

export function manifestFor(target: Target, version: string): Record<string, unknown> {
  const base = {
    manifest_version: 3,
    name: "__MSG_extName__",
    description: "__MSG_extDescription__",
    version,
    default_locale: "en",
    declarative_net_request: { rule_resources: [{ id: "youtube", enabled: true, path: "rules.json" }] },
    host_permissions: HOST_PERMISSIONS,
    content_scripts: [{ matches: PLAYER_MATCHES, js: ["content.js"], run_at: "document_idle" }],
    icons: ICONS,
    action: { default_title: "__MSG_actionTitle__", default_icon: ICONS },
    options_ui: { page: "options.html", open_in_tab: true },
  };
  const permissions = ["webNavigation", "storage", "declarativeNetRequestWithHostAccess"];
  if (target === "chrome") {
    return {
      ...base,
      // sidePanel shows the lyrics. scripting only reaches the music tabs already open when the
      // extension is installed or updated, as Chrome doesn't give those the content script itself.
      permissions: [...permissions, "sidePanel", "scripting"],
      minimum_chrome_version: "120",
      background: { service_worker: "background.js" },
      side_panel: { default_path: "panel.html" },
    };
  }
  return {
    ...base,
    permissions,
    background: { scripts: ["background.js"] },
    sidebar_action: { default_panel: "panel.html", default_title: "__MSG_actionTitle__", default_icon: ICONS, open_at_install: false },
    browser_specific_settings: {
      gecko: { id: "extension@crosstune.4st.li", strict_min_version: "142.0", data_collection_permissions: { required: ["none"] } },
    },
  };
}
