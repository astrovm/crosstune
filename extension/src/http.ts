// How the extension asks the services' own public endpoints. Nothing here is ours: no account,
// no cookies, and nothing is sent but what the service itself is asked.

export interface Init {
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;
}

/** What a service answered; [url] is where the answer came from, after any redirects. */
export interface Reply {
  status: number;
  ok: boolean;
  url: string;
  text: string;
}

export type Fetcher = (url: string, init?: Init) => Promise<Reply>;

/** A service didn't answer in time or at all, which says nothing about whether it has the song. */
export class Unreachable extends Error {}

/** At most this much of an answer is read, so a page of megabytes isn't. */
const MAX_BYTES = 8 * 1024 * 1024;

export function realFetcher(timeoutMs: number, signal?: AbortSignal): Fetcher {
  return async (url, init = {}) => {
    const limit = AbortSignal.timeout(timeoutMs);
    try {
      const response = await fetch(url, {
        method: init.method ?? "GET",
        headers: init.headers,
        body: init.body,
        credentials: "omit",
        signal: signal === undefined ? limit : AbortSignal.any([limit, signal]),
      });
      const text = await boundedText(response);
      return { status: response.status, ok: response.ok, url: response.url, text };
    } catch (error) {
      throw new Unreachable(error instanceof Error ? error.message : String(error));
    }
  };
}

async function boundedText(response: Response): Promise<string> {
  const reader = response.body?.getReader();
  if (reader === undefined) return "";
  const decoder = new TextDecoder();
  let text = "";
  let bytes = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return text + decoder.decode();
    bytes += value.length;
    text += decoder.decode(value, { stream: true });
    if (bytes >= MAX_BYTES) {
      await reader.cancel();
      return text;
    }
  }
}

export function json(body: unknown): Init {
  return { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) };
}

/** An answer that should be JSON; anything else, e.g. an error page, is no answer. */
export function parseJson(reply: Reply): unknown {
  if (!reply.ok) throw new Unreachable(`${new URL(reply.url).hostname} answered ${reply.status}`);
  try {
    return JSON.parse(reply.text);
  } catch {
    throw new Unreachable("Not JSON");
  }
}
