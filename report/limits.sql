-- What keeps the reports table from being filled, by many senders or one: the count of reports it holds and of
-- each day's, for each kind, the count of each network's reports today, and the count of reports turned away.
-- Nothing here says who sent a report. Run after schema.sql on a new database, and once on the live one before
-- deploying the Worker that uses it, from report/:
--   npx wrangler d1 execute crosstune-reports --remote --file limits.sql
-- Running it again changes nothing but the counts, which it takes again from the table.
--
-- Editing reports by hand: the triggers below keep the counts exact for INSERT, DELETE and UPDATE. Never use
-- REPLACE (or INSERT OR REPLACE): it deletes a row without its trigger, and the counts drift. After any other
-- edit the triggers don't see, run this file again.

-- When each report came in, in order: the daily clear-out finds the old ones here, and the counts start from it.
CREATE INDEX IF NOT EXISTS reports_at ON reports (at);

-- How many reports of each kind the table holds, under 'all:user', and how many came in each day, under the day
-- and kind ('2026-11-11:user', UTC). The kinds are the Worker's kindOf: 'kept' for the timing an earlier version
-- kept, 'found' for a problem the app finds live, and 'user' for what a person sends. The triggers keep them
-- exact as reports come, go and change, by hand too, so the Worker reads a few rows, never counts.
CREATE TABLE IF NOT EXISTS report_counts (key TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;

CREATE TRIGGER IF NOT EXISTS report_counted AFTER INSERT ON reports BEGIN
  INSERT INTO report_counts (key, n)
  SELECT 'all:' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  UNION ALL
  SELECT date(NEW.at) || ':' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  WHERE true ON CONFLICT (key) DO UPDATE SET n = n + 1;
END;

CREATE TRIGGER IF NOT EXISTS report_uncounted AFTER DELETE ON reports BEGIN
  UPDATE report_counts SET n = n - 1
  WHERE key IN (SELECT 'all:' || kind FROM (SELECT CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
                UNION ALL
                SELECT date(OLD.at) || ':' || kind FROM (SELECT CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind));
END;

-- A report moved to another day, or to another kind: taken off where it was counted, and counted where it is now.
CREATE TRIGGER IF NOT EXISTS report_recounted AFTER UPDATE OF at, problem ON reports BEGIN
  UPDATE report_counts SET n = n - 1
  WHERE key IN (SELECT 'all:' || kind FROM (SELECT CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
                UNION ALL
                SELECT date(OLD.at) || ':' || kind FROM (SELECT CASE WHEN OLD.problem = 'kept_timing' THEN 'kept' WHEN OLD.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind));
  INSERT INTO report_counts (key, n)
  SELECT 'all:' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  UNION ALL
  SELECT date(NEW.at) || ':' || kind, 1 FROM (SELECT CASE WHEN NEW.problem = 'kept_timing' THEN 'kept' WHEN NEW.problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind)
  WHERE true ON CONFLICT (key) DO UPDATE SET n = n + 1;
END;

-- Taken again from the table: the reports of each kind it holds, and today's and yesterday's, the only days the
-- Worker asks. Every count goes to 0 first, then each is set to what the table has, over the row that's there.
UPDATE report_counts SET n = 0;
INSERT INTO report_counts (key, n)
SELECT 'all:' || kind, count(*) FROM (SELECT CASE WHEN problem = 'kept_timing' THEN 'kept' WHEN problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind FROM reports)
WHERE true GROUP BY kind
ON CONFLICT (key) DO UPDATE SET n = excluded.n;
INSERT INTO report_counts (key, n)
SELECT day || ':' || kind, count(*) FROM (SELECT date(at) AS day, CASE WHEN problem = 'kept_timing' THEN 'kept' WHEN problem IN ('no_lyrics', 'no_timing', 'timed_by_hand', 'sung_off', 'no_chords') THEN 'found' ELSE 'user' END AS kind FROM reports WHERE at >= date('now', '-1 day'))
WHERE true GROUP BY day, kind
ON CONFLICT (key) DO UPDATE SET n = excluded.n;

-- How many reports of each kind came today from a network (an IPv4 /24 or an IPv6 /48), an IPv6 provider's block
-- (a /32), or a carrier (its autonomous system number, as Cloudflare names it). Each is under a key that is a keyed
-- hash (HMAC-SHA256) of the day, the kind and the network, block or carrier, with a secret only the Worker holds
-- (NETWORK_KEY). Without the secret, the key can't be traced back to them; it is another one each day, and the
-- daily clear-out deletes past days.
CREATE TABLE IF NOT EXISTS network_days (key TEXT PRIMARY KEY, day TEXT NOT NULL, n INTEGER NOT NULL) WITHOUT ROWID;

-- How many reports were turned away each day ('2026-11-11', UTC), why and of what kind: 'network:user' (its network
-- sent its day's), 'block:found' (its /32 did), 'carrier:kept' (its carrier did), 'day_full:user' (that kind's share
-- of the day is in), 'kept_full:user' (that kind's share of the table is), and 'no_key' (the Worker has no
-- NETWORK_KEY). reports.py's triage shows them. Counted up to 1000 a day for each, so a flood costs a write a
-- refusal only until then.
CREATE TABLE IF NOT EXISTS refusals (day TEXT NOT NULL, why TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, why)) WITHOUT ROWID;
