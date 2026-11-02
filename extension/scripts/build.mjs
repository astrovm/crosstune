// Builds the extension for Chrome (and Edge, Brave) and for Firefox into dist/chrome and dist/firefox.
//   node scripts/build.mjs

import { build } from "esbuild";
import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { HOST_PERMISSIONS } from "../src/hosts.ts";
import pkg from "../package.json" with { type: "json" };

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const ICONS = Object.fromEntries([16, 32, 48, 128].map((size) => [size, `icons/icon-${size}.png`]));

const base = {
  manifest_version: 3,
  name: "__MSG_extName__",
  description: "__MSG_extDescription__",
  version: pkg.version,
  default_locale: "en",
  permissions: ["webNavigation", "storage", "declarativeNetRequestWithHostAccess"],
  declarative_net_request: { rule_resources: [{ id: "youtube", enabled: true, path: "rules.json" }] },
  host_permissions: HOST_PERMISSIONS,
  icons: ICONS,
  action: { default_title: "__MSG_actionTitle__", default_icon: ICONS },
  options_ui: { page: "options.html", open_in_tab: true },
};

const manifests = {
  chrome: { ...base, background: { service_worker: "background.js" } },
  firefox: {
    ...base,
    background: { scripts: ["background.js"] },
    browser_specific_settings: {
      gecko: { id: "extension@crosstune.4st.li", strict_min_version: "142.0", data_collection_permissions: { required: ["none"] } },
    },
  },
};

for (const [target, manifest] of Object.entries(manifests)) {
  const out = join(root, "dist", target);
  await rm(out, { recursive: true, force: true });
  await mkdir(out, { recursive: true });
  await build({
    entryPoints: [join(root, "src/background.ts"), join(root, "src/options.ts")],
    outdir: out,
    bundle: true,
    format: "iife",
    target: ["chrome120", "firefox142"],
    legalComments: "none",
  });
  await cp(join(root, "src/options.html"), join(out, "options.html"));
  await cp(join(root, "src/rules.json"), join(out, "rules.json"));
  await cp(join(root, "_locales"), join(out, "_locales"), { recursive: true });
  await cp(join(root, "icons"), join(out, "icons"), { recursive: true });
  await writeFile(join(out, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`built dist/${target}`);
}
