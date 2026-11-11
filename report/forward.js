// The old address, report.crosstune.4st.li, which the versions of Crosstune from before crosstune.cc
// still send to. It keeps nothing: it counts a report against its sender's address, as the Worker did
// here, and passes it on to report.crosstune.cc with the key that says it was counted, and the sender's
// address, which the Worker there counts the network's day by and doesn't keep.

import { senderKey } from "./sender.js";

/** Where reports are kept now. */
const NOW = "https://report.crosstune.cc";

/** Well over the most a report may be, which the Worker it's passed on to checks to the byte. */
const MAX_BYTES = 64 * 1024;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST") return fetch(NOW + url.pathname, { method: request.method });
    if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BYTES) return new Response(null, { status: 413 });
    const sender = request.headers.get("CF-Connecting-IP");
    const { success } = await env.LIMIT.limit({ key: senderKey(sender) });
    if (!success) return new Response(null, { status: 429 });
    // The type goes on as it was sent, or not at all, as where reports are kept now only takes JSON.
    const type = request.headers.get("Content-Type");
    return fetch(NOW + url.pathname, {
      method: "POST",
      headers: { ...(type === null ? {} : { "Content-Type": type }), "X-Crosstune-Passed-On": env.FORWARD_KEY, "X-Crosstune-Sender": sender },
      body: await request.text(),
    });
  },
};
