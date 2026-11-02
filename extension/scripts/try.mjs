// Runs the extension's own resolution logic in node against the live services and prints where
// a link would go. Read-only: it asks the same public endpoints the extension does.
//   node scripts/try.mjs <url> <service>        service: spotify youtubemusic youtube applemusic deezer tidal soundcloud amazonmusic

import { decide } from "../src/destination.ts";
import { realFetcher } from "../src/http.ts";
import { SERVICES, isService } from "../src/services.ts";

const [url, service] = process.argv.slice(2);
if (url === undefined || !isService(service)) {
  console.error(`usage: node scripts/try.mjs <url> <${SERVICES.join("|")}>`);
  process.exit(2);
}
const started = Date.now();
const decision = await decide(url, service, { fetch: realFetcher(5000), country: process.env.COUNTRY ?? "" });
const took = `${Date.now() - started} ms`;
console.log(decision.kind === "go" ? `${decision.how.padEnd(6)} ${decision.url}  (${took})` : `leave  ${url}  (${took})`);
