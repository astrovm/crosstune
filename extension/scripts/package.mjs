// Zips the built extension for the stores into dist/: crosstune-<version>-chrome.zip (Chrome, Edge),
// crosstune-<version>-firefox.zip, and crosstune-<version>-source.zip, the committed extension/ folder
// that Firefox's reviewers build it again from. Needs zip and git.
//   npm run build && node scripts/package.mjs

import { execFileSync } from "node:child_process";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import pkg from "../package.json" with { type: "json" };

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");

for (const target of ["chrome", "firefox"]) {
  const zip = join(dist, `crosstune-${pkg.version}-${target}.zip`);
  await rm(zip, { force: true });
  // From inside, so manifest.json is at the zip's top, as the stores want.
  execFileSync("zip", ["-qrX", zip, "."], { cwd: join(dist, target) });
  console.log(zip);
}
const source = join(dist, `crosstune-${pkg.version}-source.zip`);
// From the repository's top: run inside extension/, git archives nothing.
execFileSync("git", ["archive", "--format=zip", "-o", source, "HEAD:extension"], { cwd: join(root, "..") });
console.log(source);
