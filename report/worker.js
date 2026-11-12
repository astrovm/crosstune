// Takes the problems people report from Crosstune and keeps them private, in D1, to be read by
// hand. Nothing here posts anywhere public: a comment, or a song, may say something personal by
// mistake. The IP address only counts reports against the limits: it isn't kept. Each network is
// counted for the day under a key made with a secret, which can't be traced back to it without the
// secret (limits.sql).

import { networkOf, senderKey } from "./sender.js";

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
 * What a report is, for the limits: one a person sends ("user", a problem in the lyrics or a reading marked wrong),
 * one the app finds live ("found"), or a song's timing an earlier version kept ("kept"). Each kind has its own room,
 * so one can't use up another's.
 */
export function kindOf(problem) {
  if (problem === KEPT_TIMING) return "kept";
  return PROBLEMS.has(problem) ? "found" : "user";
}

/** The timing an earlier version kept, as v2.24.0 on sends it (AutoReports.EARLIER). */
const KEPT_TIMING = "kept_timing";

/**
 * The most reports one network (an IPv4 /24, an IPv6 /48) may send in a day (UTC), for each kind: room for 3 phones on
 * their busiest day, as homes and carriers share addresses. In 115 real reports over 14 days, one phone sent at most
 * 3 reports of its own a day, and found at most 22 problems. One phone's first start of a new version owes up to 200
 * timings it kept, sent until one is turned away; the rest wait on the phone for a later start.
 */
const NETWORK_A_DAY = { user: 10, found: 70, kept: 200 };

/**
 * The most reports of each kind kept from one day (UTC), whoever sent them: the last wall when many networks send at
 * once. Each kind has its own share, and a sender can claim any kind, so a flood fills one share and the others hold.
 * Each is over 100 times its real peak, 3 and 22 a day, and kept timings take 15 phones' first starts. Measured on
 * local D1, the worst day, every share kept from networks of their own, 1000 refusals for each reason, and the daily
 * clear-out deleting a day as full, writes about 64,000 rows, inside the 100,000 D1's free plan writes a day.
 */
const SHARE_A_DAY = { user: 500, found: 2500, kept: 3000 };

/**
 * The most bytes of reports of each kind the table holds, as limits.sql counts them, so one kind's flood can't fill it
 * for the others. Real reports count about 200 bytes, so a kind's day share kept every day for the 180 days a report
 * is kept takes 19, 77 and 96 MB: honest use never meets these. A flood of the biggest reports the Worker takes fills
 * one in 1 to 2 days. On disk the table is about 1.03 times what's counted, so the 350 MB in all stays under the
 * 500 MB a database may hold on Cloudflare's free plan.
 */
const SHARE_BYTES = { user: 50_000_000, found: 150_000_000, kept: 150_000_000 };

/** The kinds, in the order the limits are named. */
const KINDS = ["user", "found", "kept"];

/** How many refusals for each reason a day are counted: past it, a refusal costs no write. */
const MAX_REFUSALS = 1000;

/** What every version of the app sends a report as, OkHttp's "application/json; charset=utf-8", with or without the charset. */
const JSON_TYPE = /^application\/json\s*(;\s*charset=utf-8\s*)?$/i;

/** The columns a report fills, in the order its values are bound. */
const COLUMNS =
  "version, android, title, artist, app, timed, synced, sung, length_ms, source, timing, comment, problem, app_package, speed, lead_ms, early_ms, timing_log, recording, " +
  "lyric_line, word, reading_shown, reader, language, right_reading";

/** The bytes of [kind] the table holds, and how many came in today, as limits.sql's triggers keep them. */
const heldOf = (kind) => `coalesce((SELECT n FROM report_counts WHERE key = 'bytes:${kind}'), 0)`;
const todayOf = (kind) => `coalesce((SELECT n FROM report_counts WHERE key = date('now') || ':${kind}'), 0)`;
const room = (kind) => `${heldOf(kind)} < ${SHARE_BYTES[kind]} AND ${todayOf(kind)} < ${SHARE_A_DAY[kind]}`;

/**
 * Why a report of each kind is turned away, if it is: 'kept_full' or 'day_full' for its kind's share, or 'network'
 * when its network sent its day's, else null. Bound to the network's key and its number for the kind. One read
 * before anything is written, so a report turned away costs a refusal's write at most, and no count.
 */
const CHECK = Object.fromEntries(KINDS.map((kind) => [kind,
  `SELECT CASE WHEN ${heldOf(kind)} >= ${SHARE_BYTES[kind]} THEN 'kept_full' WHEN ${todayOf(kind)} >= ${SHARE_A_DAY[kind]} THEN 'day_full' ` +
  "WHEN coalesce((SELECT n FROM network_days WHERE key = ?1), 0) >= ?2 THEN 'network' END AS why",
]));

/**
 * A report of each kind put in the table, unless that would take its kind past its share of the table or the day: one
 * statement, so two reports at once can't both take the last place. It reads two counts, one row each, never the reports.
 */
const INSERT = Object.fromEntries(KINDS.map((kind) => [kind,
  `INSERT INTO reports (${COLUMNS}) SELECT ${COLUMNS.split(",").map(() => "?").join(", ")} WHERE ${room(kind)}`,
]));

/** One more report from a network today, unless it sent its day's: then nothing changes. */
const COUNT_NETWORK = "INSERT INTO network_days (key, day, n) VALUES (?, ?, 1) ON CONFLICT (key) DO UPDATE SET n = n + 1 WHERE n < ?";

/** A network's report taken back, as its kind's share filled before it was kept; a count back at nothing goes. */
const UNCOUNT_NETWORK = ["UPDATE network_days SET n = n - 1 WHERE key = ?", "DELETE FROM network_days WHERE key = ? AND n <= 0"];

/** One more report turned away today, for the reason given, up to [MAX_REFUSALS]. */
const REFUSE = `INSERT INTO refusals (day, why, n) VALUES (date('now'), ?, 1) ON CONFLICT (day, why) DO UPDATE SET n = n + 1 WHERE n < ${MAX_REFUSALS}`;

/** What the daily clear-out deletes: reports past the days they're kept, then the counts no one asks anymore. */
const CLEAR_OUT = [
  ["DELETE FROM reports WHERE at < datetime('now', ?)", `-${KEEP_DAYS} days`],
  ["DELETE FROM network_days WHERE day < date('now')"],
  ["DELETE FROM report_counts WHERE key NOT LIKE 'bytes:%' AND key < date('now', '-1 day')"],
  ["DELETE FROM refusals WHERE day < date('now', ?)", `-${KEEP_DAYS} days`],
];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/reports") return status(404);
    if (request.method !== "POST") return status(405);
    // Only as JSON, as the app sends it. A web page can make its visitors' browsers send plain text or a form
    // anywhere, without asking first; JSON needs the receiver's leave, which this never gives.
    if (!JSON_TYPE.test(request.headers.get("Content-Type") ?? "")) return status(415);
    if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BYTES) return status(413);
    // A report the old address passes on (forward.js) was counted there for the minute, against its sender's own
    // address, which it passes on too. From here they all come from Cloudflare's, and would count as one sender.
    const passedOn = await sameKey(request.headers.get("X-Crosstune-Passed-On"), env.FORWARD_KEY);
    const address = (passedOn ? request.headers.get("X-Crosstune-Sender") : null) ?? request.headers.get("CF-Connecting-IP");
    if (!passedOn) {
      const { success } = await env.LIMIT.limit({ key: senderKey(address) });
      if (!success) return refused(429, "minute");
    }
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BYTES) return status(413);
    const report = readReport(text);
    if (!report) return status(400);
    // Without the secret, a network can't be counted without keeping its address, so nothing is kept: the limits
    // are what keep one sender from turning everyone else's reports away. Deploying asks for it (secrets.required in
    // wrangler.jsonc), so only a mistake gets here.
    if (!env.NETWORK_KEY) {
      console.error("NETWORK_KEY isn't set: no report is kept until it is (wrangler secret put NETWORK_KEY)");
      await refuse(env, "no_key");
      return refused(503, "no_key");
    }
    const day = new Date().toISOString().slice(0, 10);
    const kind = kindOf(report.problem);
    const key = await networkDayKey(env.NETWORK_KEY, day, kind, networkOf(address));
    const most = NETWORK_A_DAY[kind];
    const check = async () => (await env.DB.prepare(CHECK[kind]).bind(key, most).first())?.why;
    const why = await check();
    if (why) return turnAway(env, kind, why);
    const counted = await env.DB.prepare(COUNT_NETWORK).bind(key, day, most).run();
    // Another report from the network took its last one meanwhile.
    if (counted.meta.changes === 0) return turnAway(env, kind, "network");
    const { meta } = await env.DB.prepare(INSERT[kind]).bind(
      report.version, report.android, report.title, report.artist, report.app,
      report.timed, report.synced, report.sung, report.lengthMs, report.source, report.timing, report.comment, report.problem,
      report.appPackage, report.speed, report.leadMs, report.earlyMs, report.timingLog, report.recording,
      report.line, report.word, report.shown, report.reader, report.language, report.right
    ).run();
    if (meta.changes !== 0) return status(204);
    // The kind's share filled meanwhile, so the network didn't send this one after all.
    for (const sql of UNCOUNT_NETWORK) await env.DB.prepare(sql).bind(key).run();
    return turnAway(env, kind, (await check()) ?? "day_full");
  },

  // Once a day, the reports older than the policy allows go, and the counts no one asks anymore. Each delete runs
  // whatever happened to the others, so one that fails can't keep old reports past the days the policy promises;
  // the run still fails after, with each error, so it's seen.
  async scheduled(controller, env) {
    const errors = [];
    for (const [sql, ...values] of CLEAR_OUT) {
      try {
        await env.DB.prepare(sql).bind(...values).run();
      } catch (error) {
        console.error(`The daily clear-out couldn't run ${sql}:`, error);
        errors.push(error);
      }
    }
    if (errors.length > 0) throw new AggregateError(errors, "The daily clear-out failed in part");
  },
};

function status(code) {
  return new Response(null, { status: code });
}

/** A report of [kind] turned away for [why] ("network", "day_full" or "kept_full"): counted, and answered with 429. */
async function turnAway(env, kind, why) {
  await refuse(env, `${why}:${kind}`);
  return refused(429, why.endsWith("_full") ? "full" : why);
}

/**
 * A report turned away, saying [why]: "minute", "network", "full" or "no_key". The app reads only the status. From
 * v2.26.0 it keeps a problem it found, or a timing it owes, and sends it at a later start; a person's own report says
 * it wasn't sent. Earlier versions keep only what they owe. The reason is for whoever looks at the answer.
 */
function refused(code, why) {
  return new Response(null, { status: code, headers: { "X-Crosstune-Refused": why } });
}

/** Counts a report turned away today for [why], up to [MAX_REFUSALS]. */
async function refuse(env, why) {
  await env.DB.prepare(REFUSE).bind(why).run();
}

/**
 * Whether [given] is [key], compared in constant time: both are hashed with SHA-256 first, so the compare is always
 * of 32 bytes, and how long it takes says nothing of how much of the key was right. No key, or an empty one, is none.
 */
export async function sameKey(given, key) {
  if (!given || !key) return false;
  const [a, b] = await Promise.all([given, key].map(async (it) => new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(it)))));
  let differ = 0;
  for (let i = 0; i < a.length; i++) differ |= a[i] ^ b[i];
  return differ === 0;
}

/**
 * The key a [network]'s reports of [kind] are counted under on [day]: an HMAC-SHA256 of all three with [secret]. It
 * can't be traced back to the network without the secret, and it is another one each day.
 */
export async function networkDayKey(secret, day, kind, network) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${day}\n${kind}\n${network}`));
  return Array.from(new Uint8Array(mac), (byte) => byte.toString(16).padStart(2, "0")).join("");
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
