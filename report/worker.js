// Takes the problems people report from Crosstune and keeps them private, in D1, to be read by
// hand. Nothing here posts anywhere public: a comment, or a song, may say something personal by
// mistake. The IP address only counts reports against the limit; it isn't kept.

import { senderKey } from "./sender.js";

/** More than a report with a long comment, every line put in time by hand and a minute of timing log needs. */
const MAX_BYTES = 48 * 1024;

/** The longest each text may be, in characters. */
const LIMITS = {
  version: 40, android: 40, title: 300, artist: 300, app: 100, source: 100, comment: 2000, line: 300, appPackage: 150, log: 8192, recording: 200,
  word: 100, shown: 300, reader: 100, language: 20, right: 100,
};

/**
 * The problems the app finds with a song by itself, and reports when the user lets it. "kept_timing" is a song's
 * timing an earlier version kept, sent once: by hand or by ear, the app can't tell.
 */
const PROBLEMS = new Set(["no_lyrics", "no_timing", "timed_by_hand", "sung_off", "no_chords", "kept_timing"]);

/** A reading the user marked wrong, on the word itself: sent only when they tap Send. */
const WRONG_READING = "wrong_reading";

/** What read a word, as the app names it, joined by "+" when more than one did: "ipadic+unidic". */
const READER = /^[a-z_]+(\+[a-z_]+)*$/;

/** The script a word was read in, as the app names it: "japanese". */
const LANGUAGE = /^[a-z_]+$/;

/** What Android allows a package to be named: dotted words of letters, digits and underscores. */
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/** The most lines put in time by hand a report keeps: the first ones, when the app sent more. */
const MAX_POINTS = 300;

/** How long a report is kept, as the privacy policy promises. Not exported: Workers take every export for an entrypoint. */
const KEEP_DAYS = 180;

/**
 * The most reports kept from the last 24 hours, whoever sent them: the last defence when many senders fill it
 * at once, past the limit for each one. People send about 7 a day. One phone's first start of a new version can
 * owe up to 200, each song an earlier version kept in time. An app turned away keeps what it owes for later.
 */
const MAX_A_DAY = 500;

/**
 * The most reports the table holds. A report is at most [MAX_BYTES], about 52 KB once kept, so 9000 is under the
 * 500 MB a database may hold on Cloudflare's free plan. 180 days of people's reports, at 7 a day, is about 1300.
 */
const MAX_KEPT = 9000;

/** What every version of the app sends a report as, OkHttp's "application/json; charset=utf-8", with or without the charset. */
const JSON_TYPE = /^application\/json\s*(;\s*charset=utf-8\s*)?$/i;

/** The columns a report fills, in the order its values are bound. */
const COLUMNS =
  "version, android, title, artist, app, timed, synced, sung, length_ms, source, timing, comment, problem, app_package, speed, lead_ms, early_ms, timing_log, recording, " +
  "lyric_line, word, reading_shown, reader, language, right_reading";

/**
 * A report put in the table, unless that would take it past [MAX_KEPT], or the last 24 hours past [MAX_A_DAY]: one
 * statement, so two reports at once can't both take the last place. Ids only grow, as only the oldest are deleted, so
 * the newest id less the oldest counts the rows, and less the first of the last day counts the day's. Each is one
 * row looked up at an end of the table or of the reports_at index, never a count of every row, so a full table
 * costs a flood of reports nothing. A gap left by deleting by hand only counts as more.
 */
const INSERT =
  `INSERT INTO reports (${COLUMNS}) SELECT ${COLUMNS.split(",").map(() => "?").join(", ")} WHERE ` +
  `coalesce((SELECT max(id) FROM reports) - (SELECT min(id) FROM reports) + 1, 0) < ${MAX_KEPT} AND ` +
  `coalesce((SELECT max(id) FROM reports) - (SELECT id FROM reports WHERE at > datetime('now', '-1 day') ORDER BY at LIMIT 1) + 1, 0) < ${MAX_A_DAY}`;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/reports") return status(404);
    if (request.method !== "POST") return status(405);
    // Only as JSON, as the app sends it. A web page can make its visitors' browsers send plain text or a form
    // anywhere, without asking first; JSON needs the receiver's leave, which this never gives.
    if (!JSON_TYPE.test(request.headers.get("Content-Type") ?? "")) return status(415);
    if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BYTES) return status(413);
    // A report the old address passes on (forward.js) was counted there, against its sender's own
    // address. From here they all come from Cloudflare's, and would count as one sender.
    const passedOn = !!env.FORWARD_KEY && request.headers.get("X-Crosstune-Passed-On") === env.FORWARD_KEY;
    if (!passedOn) {
      const { success } = await env.LIMIT.limit({ key: senderKey(request.headers.get("CF-Connecting-IP")) });
      if (!success) return status(429);
    }
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BYTES) return status(413);
    const report = readReport(text);
    if (!report) return status(400);
    const { meta } = await env.DB.prepare(INSERT).bind(
      report.version, report.android, report.title, report.artist, report.app,
      report.timed, report.synced, report.sung, report.lengthMs, report.source, report.timing, report.comment, report.problem,
      report.appPackage, report.speed, report.leadMs, report.earlyMs, report.timingLog, report.recording,
      report.line, report.word, report.shown, report.reader, report.language, report.right
    ).run();
    // Full: the app keeps what it owes and tries again later.
    return status(meta.changes === 0 ? 429 : 204);
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
    comment: textOf(json.comment, LIMITS.comment, LINES),
    // A problem it doesn't know is one the user sent, as far as it can tell.
    problem: PROBLEMS.has(json.problem) || json.problem === WRONG_READING ? json.problem : null,
    title: null, artist: null, app: null, timed: null, synced: null, sung: null, lengthMs: null, source: null, timing: null,
    appPackage: null, speed: null, leadMs: null, earlyMs: null, timingLog: null, recording: null,
    line: null, word: null, shown: null, reader: null, language: null, right: null,
  };
  if (report.problem === WRONG_READING && !readingOf(json.reading, song, report)) return null;
  if (song) {
    report.title = textOf(song.title, LIMITS.title);
    report.artist = textOf(song.artist, LIMITS.artist);
    if (!report.title || !report.artist) return null;
    report.app = textOf(song.app, LIMITS.app);
    report.timed = flag(song.timed);
    report.synced = flag(song.synced);
    report.sung = flag(song.sung);
    report.lengthMs = wholeMs(song.lengthMs);
    report.source = textOf(song.source, LIMITS.source);
    // What a report about the words' timing adds: which app was followed, and how it went. Old apps send none.
    const appPackage = textOf(song.appPackage, LIMITS.appPackage);
    report.appPackage = appPackage !== null && PACKAGE.test(appPackage) ? appPackage : null;
    report.speed = speedOf(song.speed);
    report.leadMs = signedMs(song.leadMs);
    report.earlyMs = signedMs(song.earlyMs);
    report.timingLog = textOf(song.log, LIMITS.log, LINES);
    // Which recording the timing was kept for, as the app named it: "SPOTIFY:id", or the app's own "pkg:id".
    report.recording = textOf(song.recording, LIMITS.recording);
    const timing = timingOf(song.timing);
    if (timing === undefined) return null;
    report.timing = timing;
  }
  return report;
}

/**
 * The wrong reading in [value] put in [report]: the line, the word, the reading shown, what read it, its
 * script and the right one when the user said it. False when it isn't one: a song, a word and a reading are a must.
 */
function readingOf(value, song, report) {
  if (!song || !isObject(value)) return false;
  report.line = textOf(value.line, LIMITS.line);
  report.word = textOf(value.word, LIMITS.word);
  report.shown = textOf(value.shown, LIMITS.shown);
  report.reader = matching(textOf(value.reader, LIMITS.reader), READER);
  report.language = matching(textOf(value.language, LIMITS.language), LANGUAGE);
  report.right = textOf(value.right, LIMITS.right);
  return report.word !== null && report.shown !== null;
}

/** [text] when it's [pattern], or null. */
function matching(text, pattern) {
  return text !== null && pattern.test(text) ? text : null;
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Says [textOf] keeps a text's line breaks: only a comment and the timing log have more than one line. */
const LINES = true;

/** A line break, however it's written. */
const BREAK = /\r\n?|[\n\u2028\u2029]/g;

/**
 * What's never kept: control characters, which can move a terminal's cursor, change its colors or its title when
 * a report is printed, and the marks that turn the direction of the text after them around. Only "\n" stays, as
 * every line break is made one, or a space, before.
 */
const UNSEEN = /[\u0000-\u0009\u000b-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g;

/**
 * [value] as text up to [max] characters, or null when it isn't text or is blank. A tab is a space, and so is a
 * line break, unless the text keeps its [lines]; then every break is "\n".
 */
function textOf(value, max, lines = false) {
  if (typeof value !== "string") return null;
  const trimmed = value.replace(BREAK, lines ? "\n" : " ").replace(/\t/g, " ").replace(UNSEEN, "").trim();
  return trimmed ? Array.from(trimmed).slice(0, max).join("") : null;
}

function flag(value) {
  return typeof value === "boolean" ? Number(value) : null;
}

/** [value] as milliseconds, when it's a whole number a song could last or be at. */
function wholeMs(value) {
  return Number.isInteger(value) && value >= 0 && value <= 24 * 60 * 60 * 1000 ? value : null;
}

/** [value] as milliseconds, before or after: a whole number no more than a day off. */
function signedMs(value) {
  return Number.isInteger(value) && Math.abs(value) <= 24 * 60 * 60 * 1000 ? value : null;
}

/** [value] as a playback speed in hundredths, when it's one a player could have. */
function speedOf(value) {
  return typeof value === "number" && value > 0 && value <= 16 ? Math.round(value * 100) / 100 : null;
}

/**
 * The first [MAX_POINTS] lines put in time by hand, as JSON, each with where the words had it and where it's
 * heard: null when there are none, undefined when they aren't lines put in time.
 */
function timingOf(value) {
  if (value === undefined || value === null) return null;
  if (!Array.isArray(value)) return undefined;
  const points = [];
  for (const point of value.slice(0, MAX_POINTS)) {
    if (!isObject(point)) return undefined;
    const timedMs = wholeMs(point.timedMs);
    const heardMs = signedMs(point.heardMs);
    if (timedMs === null || heardMs === null) return undefined;
    points.push({ line: textOf(point.line, LIMITS.line), timedMs, heardMs });
  }
  return points.length ? JSON.stringify(points) : null;
}
