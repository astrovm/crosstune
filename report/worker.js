// Takes the problems people report from Crosstune and keeps them private, in D1, to be read by
// hand. Nothing here posts anywhere public: a comment, or a song, may say something personal by
// mistake. The IP address only counts reports against the limit; it isn't kept.

/** More than a report with a long comment and every line put in time by hand needs. */
const MAX_BYTES = 32 * 1024;

/** The longest each text may be, in characters. */
const LIMITS = { version: 40, android: 40, title: 300, artist: 300, app: 100, comment: 2000, line: 300 };

/** The most lines put in time by hand a report carries. */
const MAX_POINTS = 300;

/** How long a report is kept, as the privacy policy promises. Not exported: Workers take every export for an entrypoint. */
const KEEP_DAYS = 180;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/reports") return status(404);
    if (request.method !== "POST") return status(405);
    if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BYTES) return status(413);
    const { success } = await env.LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") ?? "" });
    if (!success) return status(429);
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BYTES) return status(413);
    const report = readReport(text);
    if (!report) return status(400);
    await env.DB.prepare(
      "INSERT INTO reports (version, android, title, artist, app, timed, synced, sung, length_ms, timing, comment) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ).bind(
      report.version, report.android, report.title, report.artist, report.app,
      report.timed, report.synced, report.sung, report.lengthMs, report.timing, report.comment
    ).run();
    return status(204);
  },

  // Once a day, the reports older than the policy allows go.
  async scheduled(controller, env) {
    await env.DB.prepare("DELETE FROM reports WHERE at < datetime('now', ?)").bind(`-${KEEP_DAYS} days`).run();
  },
};

function status(code) {
  return new Response(null, { status: code });
}

/** The report in [text], with only what a report has, or null when it isn't one. */
export function readReport(text) {
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isObject(json)) return null;
  const version = textOf(json.version, LIMITS.version);
  if (!version) return null;
  const song = json.song === undefined || json.song === null ? null : json.song;
  if (song !== null && !isObject(song)) return null;
  const report = {
    version,
    android: textOf(json.android, LIMITS.android),
    comment: textOf(json.comment, LIMITS.comment),
    title: null, artist: null, app: null, timed: null, synced: null, sung: null, lengthMs: null, timing: null,
  };
  if (song) {
    report.title = textOf(song.title, LIMITS.title);
    report.artist = textOf(song.artist, LIMITS.artist);
    if (!report.title || !report.artist) return null;
    report.app = textOf(song.app, LIMITS.app);
    report.timed = flag(song.timed);
    report.synced = flag(song.synced);
    report.sung = flag(song.sung);
    report.lengthMs = wholeMs(song.lengthMs);
    const timing = timingOf(song.timing);
    if (timing === undefined) return null;
    report.timing = timing;
  }
  return report;
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** [value] as text up to [max] characters, or null when it isn't text or is blank. */
function textOf(value, max) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? Array.from(trimmed).slice(0, max).join("") : null;
}

function flag(value) {
  return typeof value === "boolean" ? Number(value) : null;
}

/** [value] as milliseconds, when it's a whole number a song could last or be at. */
function wholeMs(value) {
  return Number.isInteger(value) && value >= 0 && value <= 24 * 60 * 60 * 1000 ? value : null;
}

/**
 * The lines put in time by hand, as JSON, each with where the words had it and where it's heard:
 * null when there are none, undefined when they aren't lines put in time.
 */
function timingOf(value) {
  if (value === undefined || value === null) return null;
  if (!Array.isArray(value) || value.length > MAX_POINTS) return undefined;
  const points = [];
  for (const point of value) {
    if (!isObject(point)) return undefined;
    const timedMs = wholeMs(point.timedMs);
    const heardMs = Number.isInteger(point.heardMs) && Math.abs(point.heardMs) <= 24 * 60 * 60 * 1000 ? point.heardMs : null;
    if (timedMs === null || heardMs === null) return undefined;
    points.push({ line: textOf(point.line, LIMITS.line), timedMs, heardMs });
  }
  return points.length ? JSON.stringify(points) : null;
}
