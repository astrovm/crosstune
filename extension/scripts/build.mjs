// Builds the extension for Chrome (and Edge, Brave) and for Firefox into dist/chrome and dist/firefox.
//   node scripts/build.mjs

import { build } from "esbuild";
import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { manifestFor } from "../src/manifest.ts";
import pkg from "../package.json" with { type: "json" };

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const manifests = { chrome: manifestFor("chrome", pkg.version), firefox: manifestFor("firefox", pkg.version) };

for (const [target, manifest] of Object.entries(manifests)) {
  const out = join(root, "dist", target);
  await rm(out, { recursive: true, force: true });
  await mkdir(out, { recursive: true });
  await build({
    entryPoints: ["background", "content", "options", "panel"].map((name) => join(root, "src", `${name}.ts`)),
    outdir: out,
    bundle: true,
    format: "iife",
    target: ["chrome120", "firefox142"],
    legalComments: "none",
  });
  await cp(join(root, "src/options.html"), join(out, "options.html"));
  await cp(join(root, "src/panel.html"), join(out, "panel.html"));
  await cp(join(root, "src/rules.json"), join(out, "rules.json"));
  await cp(join(root, "_locales"), join(out, "_locales"), { recursive: true });
  await cp(join(root, "icons"), join(out, "icons"), { recursive: true });
  await writeFile(join(out, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`built dist/${target}`);
}
