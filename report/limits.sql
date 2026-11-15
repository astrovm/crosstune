-- What keeps the reports table from being filled, by many senders or one: the bytes of each kind of report it holds,
-- how many of each kind came each day, how many each network sent today, and how many were turned away.
-- Nothing here says who sent a report. Run after schema.sql on a new database, and once on the live one before
-- deploying the Worker that uses it, from report/:
--   npx wrangler d1 execute crosstune-reports --remote --file limits.sql
-- Running it again changes nothing but the counts, which it takes again from the table, and the triggers, which it
-- makes again as they are here, so a column added to the reports is counted once this runs after it.
--
-- Editing reports by hand: the triggers below keep the counts exact for INSERT, DELETE and UPDATE. Never use
-- REPLACE (or INSERT OR REPLACE): it deletes a row without its trigger, and the counts drift. After any other
-- edit the triggers don't see, run this file again.

-- The triggers below count report_id, which migrations/0001_report_id.sql adds. Without it, this stops here, before
-- any trigger is dropped, so the ones there keep counting: run that migration first.
SELECT report_id FROM reports LIMIT 0;

-- When each report came in, in order: the daily clear-out finds the old ones here, and the counts start from it.
CREATE INDEX IF NOT EXISTS reports_at ON reports (at);

-- The bytes of each kind of report the table holds, under 'bytes:user', and how many came in each day, under the day
-- and kind ('2026-11-11:user', UTC). The kinds are the Worker's kindOf: 'kept' for the timing an earlier version
-- kept, 'found' for a problem the app finds live, and 'user' for what a person sends. A report's bytes are those of
-- each value it holds, its id twice, as its index holds it too, and 64 for the row around them, so a share of the table is a share of the database, whether
-- reports are small, as real ones are, or as big as the Worker takes. The triggers keep them exact as reports come,
-- go and change, by hand too, so the Worker reads a few rows, never counts.
CREATE TABLE IF NOT EXISTS report_counts (key TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;

DROP TRIGGER IF EXISTS report_counted;
CREATE TRIGGER report_counted AFTER INSERT ON reports BEGIN
  INSERT INTO report_counts (key, n)
  SELECT 'bytes:' || kind, size FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind, (64 + coalesce(length(CAST(NEW.at AS BLOB)), 0) + coalesce(length(CAST(NEW.version AS BLOB)), 0) + coalesce(length(CAST(NEW.android AS BLOB)), 0) + coalesce(length(CAST(NEW.title AS BLOB)), 0) + coalesce(length(CAST(NEW.artist AS BLOB)), 0) + coalesce(length(CAST(NEW.app AS BLOB)), 0) + coalesce(length(CAST(NEW.timed AS BLOB)), 0) + coalesce(length(CAST(NEW.synced AS BLOB)), 0) + coalesce(length(CAST(NEW.sung AS BLOB)), 0) + coalesce(length(CAST(NEW.length_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.source AS BLOB)), 0) + coalesce(length(CAST(NEW.timing AS BLOB)), 0) + coalesce(length(CAST(NEW.comment AS BLOB)), 0) + coalesce(length(CAST(NEW.problem AS BLOB)), 0) + coalesce(length(CAST(NEW.app_package AS BLOB)), 0) + coalesce(length(CAST(NEW.speed AS BLOB)), 0) + coalesce(length(CAST(NEW.lead_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.early_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.timing_log AS BLOB)), 0) + coalesce(length(CAST(NEW.recording AS BLOB)), 0) + coalesce(length(CAST(NEW.lyric_line AS BLOB)), 0) + coalesce(length(CAST(NEW.word AS BLOB)), 0) + coalesce(length(CAST(NEW.reading_shown AS BLOB)), 0) + coalesce(length(CAST(NEW.reader AS BLOB)), 0) + coalesce(length(CAST(NEW.language AS BLOB)), 0) + coalesce(length(CAST(NEW.right_reading AS BLOB)), 0) + 2 * coalesce(length(CAST(NEW.report_id AS BLOB)), 0)) AS size)
  UNION ALL
  SELECT date(NEW.at) || ':' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  WHERE true ON CONFLICT (key) DO UPDATE SET n = n + excluded.n;
END;

DROP TRIGGER IF EXISTS report_uncounted;
CREATE TRIGGER report_uncounted AFTER DELETE ON reports BEGIN
  UPDATE report_counts SET n = n - (SELECT (64 + coalesce(length(CAST(OLD.at AS BLOB)), 0) + coalesce(length(CAST(OLD.version AS BLOB)), 0) + coalesce(length(CAST(OLD.android AS BLOB)), 0) + coalesce(length(CAST(OLD.title AS BLOB)), 0) + coalesce(length(CAST(OLD.artist AS BLOB)), 0) + coalesce(length(CAST(OLD.app AS BLOB)), 0) + coalesce(length(CAST(OLD.timed AS BLOB)), 0) + coalesce(length(CAST(OLD.synced AS BLOB)), 0) + coalesce(length(CAST(OLD.sung AS BLOB)), 0) + coalesce(length(CAST(OLD.length_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.source AS BLOB)), 0) + coalesce(length(CAST(OLD.timing AS BLOB)), 0) + coalesce(length(CAST(OLD.comment AS BLOB)), 0) + coalesce(length(CAST(OLD.problem AS BLOB)), 0) + coalesce(length(CAST(OLD.app_package AS BLOB)), 0) + coalesce(length(CAST(OLD.speed AS BLOB)), 0) + coalesce(length(CAST(OLD.lead_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.early_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.timing_log AS BLOB)), 0) + coalesce(length(CAST(OLD.recording AS BLOB)), 0) + coalesce(length(CAST(OLD.lyric_line AS BLOB)), 0) + coalesce(length(CAST(OLD.word AS BLOB)), 0) + coalesce(length(CAST(OLD.reading_shown AS BLOB)), 0) + coalesce(length(CAST(OLD.reader AS BLOB)), 0) + coalesce(length(CAST(OLD.language AS BLOB)), 0) + coalesce(length(CAST(OLD.right_reading AS BLOB)), 0) + 2 * coalesce(length(CAST(OLD.report_id AS BLOB)), 0))) WHERE key = 'bytes:' || (CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END);
  UPDATE report_counts SET n = n - 1 WHERE key = date(OLD.at) || ':' || (CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END);
END;

-- A report changed: taken off as it was, and counted as it is now, so a day, a kind or a size changed by hand moves.
DROP TRIGGER IF EXISTS report_recounted;
CREATE TRIGGER report_recounted AFTER UPDATE ON reports BEGIN
  UPDATE report_counts SET n = n - (SELECT (64 + coalesce(length(CAST(OLD.at AS BLOB)), 0) + coalesce(length(CAST(OLD.version AS BLOB)), 0) + coalesce(length(CAST(OLD.android AS BLOB)), 0) + coalesce(length(CAST(OLD.title AS BLOB)), 0) + coalesce(length(CAST(OLD.artist AS BLOB)), 0) + coalesce(length(CAST(OLD.app AS BLOB)), 0) + coalesce(length(CAST(OLD.timed AS BLOB)), 0) + coalesce(length(CAST(OLD.synced AS BLOB)), 0) + coalesce(length(CAST(OLD.sung AS BLOB)), 0) + coalesce(length(CAST(OLD.length_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.source AS BLOB)), 0) + coalesce(length(CAST(OLD.timing AS BLOB)), 0) + coalesce(length(CAST(OLD.comment AS BLOB)), 0) + coalesce(length(CAST(OLD.problem AS BLOB)), 0) + coalesce(length(CAST(OLD.app_package AS BLOB)), 0) + coalesce(length(CAST(OLD.speed AS BLOB)), 0) + coalesce(length(CAST(OLD.lead_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.early_ms AS BLOB)), 0) + coalesce(length(CAST(OLD.timing_log AS BLOB)), 0) + coalesce(length(CAST(OLD.recording AS BLOB)), 0) + coalesce(length(CAST(OLD.lyric_line AS BLOB)), 0) + coalesce(length(CAST(OLD.word AS BLOB)), 0) + coalesce(length(CAST(OLD.reading_shown AS BLOB)), 0) + coalesce(length(CAST(OLD.reader AS BLOB)), 0) + coalesce(length(CAST(OLD.language AS BLOB)), 0) + coalesce(length(CAST(OLD.right_reading AS BLOB)), 0) + 2 * coalesce(length(CAST(OLD.report_id AS BLOB)), 0))) WHERE key = 'bytes:' || (CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END);
  UPDATE report_counts SET n = n - 1 WHERE key = date(OLD.at) || ':' || (CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END);
  INSERT INTO report_counts (key, n)
  SELECT 'bytes:' || kind, size FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind, (64 + coalesce(length(CAST(NEW.at AS BLOB)), 0) + coalesce(length(CAST(NEW.version AS BLOB)), 0) + coalesce(length(CAST(NEW.android AS BLOB)), 0) + coalesce(length(CAST(NEW.title AS BLOB)), 0) + coalesce(length(CAST(NEW.artist AS BLOB)), 0) + coalesce(length(CAST(NEW.app AS BLOB)), 0) + coalesce(length(CAST(NEW.timed AS BLOB)), 0) + coalesce(length(CAST(NEW.synced AS BLOB)), 0) + coalesce(length(CAST(NEW.sung AS BLOB)), 0) + coalesce(length(CAST(NEW.length_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.source AS BLOB)), 0) + coalesce(length(CAST(NEW.timing AS BLOB)), 0) + coalesce(length(CAST(NEW.comment AS BLOB)), 0) + coalesce(length(CAST(NEW.problem AS BLOB)), 0) + coalesce(length(CAST(NEW.app_package AS BLOB)), 0) + coalesce(length(CAST(NEW.speed AS BLOB)), 0) + coalesce(length(CAST(NEW.lead_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.early_ms AS BLOB)), 0) + coalesce(length(CAST(NEW.timing_log AS BLOB)), 0) + coalesce(length(CAST(NEW.recording AS BLOB)), 0) + coalesce(length(CAST(NEW.lyric_line AS BLOB)), 0) + coalesce(length(CAST(NEW.word AS BLOB)), 0) + coalesce(length(CAST(NEW.reading_shown AS BLOB)), 0) + coalesce(length(CAST(NEW.reader AS BLOB)), 0) + coalesce(length(CAST(NEW.language AS BLOB)), 0) + coalesce(length(CAST(NEW.right_reading AS BLOB)), 0) + 2 * coalesce(length(CAST(NEW.report_id AS BLOB)), 0)) AS size)
  UNION ALL
  SELECT date(NEW.at) || ':' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  WHERE true ON CONFLICT (key) DO UPDATE SET n = n + excluded.n;
END;

-- Taken again from the table: the bytes of each kind it holds, and today's and yesterday's reports, the only days the
-- Worker asks. Every count goes to 0 first, then each is set to what the table has, over the row that's there.
UPDATE report_counts SET n = 0;
INSERT INTO report_counts (key, n)
SELECT 'bytes:' || kind, sum(size) FROM (SELECT CASE WHEN problem = 'kept_timing' THEN 'kept' WHEN problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind, (64 + coalesce(length(CAST(at AS BLOB)), 0) + coalesce(length(CAST(version AS BLOB)), 0) + coalesce(length(CAST(android AS BLOB)), 0) + coalesce(length(CAST(title AS BLOB)), 0) + coalesce(length(CAST(artist AS BLOB)), 0) + coalesce(length(CAST(app AS BLOB)), 0) + coalesce(length(CAST(timed AS BLOB)), 0) + coalesce(length(CAST(synced AS BLOB)), 0) + coalesce(length(CAST(sung AS BLOB)), 0) + coalesce(length(CAST(length_ms AS BLOB)), 0) + coalesce(length(CAST(source AS BLOB)), 0) + coalesce(length(CAST(timing AS BLOB)), 0) + coalesce(length(CAST(comment AS BLOB)), 0) + coalesce(length(CAST(problem AS BLOB)), 0) + coalesce(length(CAST(app_package AS BLOB)), 0) + coalesce(length(CAST(speed AS BLOB)), 0) + coalesce(length(CAST(lead_ms AS BLOB)), 0) + coalesce(length(CAST(early_ms AS BLOB)), 0) + coalesce(length(CAST(timing_log AS BLOB)), 0) + coalesce(length(CAST(recording AS BLOB)), 0) + coalesce(length(CAST(lyric_line AS BLOB)), 0) + coalesce(length(CAST(word AS BLOB)), 0) + coalesce(length(CAST(reading_shown AS BLOB)), 0) + coalesce(length(CAST(reader AS BLOB)), 0) + coalesce(length(CAST(language AS BLOB)), 0) + coalesce(length(CAST(right_reading AS BLOB)), 0) + 2 * coalesce(length(CAST(report_id AS BLOB)), 0)) AS size FROM reports)
WHERE true GROUP BY kind
ON CONFLICT (key) DO UPDATE SET n = excluded.n;
INSERT INTO report_counts (key, n)
SELECT day || ':' || kind, count(*) FROM (SELECT date(at) AS day, CASE WHEN problem = 'kept_timing' THEN 'kept' WHEN problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind FROM reports WHERE at >= date('now', '-1 day'))
WHERE true GROUP BY day, kind
ON CONFLICT (key) DO UPDATE SET n = excluded.n;

-- How many reports of each kind came today from a network: an IPv4 /24, or an IPv6 /48. Each is under a key that is a
-- keyed hash (HMAC-SHA256) of the day, the kind and the network, with a secret only the Worker holds (NETWORK_KEY).
-- Without the secret, the key can't be traced back to the network; it is another one each day, and the daily
-- clear-out deletes past days.
CREATE TABLE IF NOT EXISTS network_days (key TEXT PRIMARY KEY, day TEXT NOT NULL, n INTEGER NOT NULL) WITHOUT ROWID;

-- How many reports were turned away each day ('2026-11-11', UTC), why and of what kind: 'network:user' (its network
-- sent its day's), 'day_full:user' (that kind's share of the day is in), 'kept_full:user' (that kind's share of the
-- table is), and 'no_key' (the Worker has no NETWORK_KEY). reports.py's triage shows them. Counted up to 1000 a day
-- for each, so a flood costs a write a refusal only until then.
CREATE TABLE IF NOT EXISTS refusals (day TEXT NOT NULL, why TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, why)) WITHOUT ROWID;
