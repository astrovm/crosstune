-- What keeps the reports table from being filled, by many senders or one: the count of reports it holds and of
-- each day's, the count of each network's reports today, and the count of reports turned away. Nothing here says
-- who sent a report. Run after schema.sql on a new database, and once on the live one before deploying the Worker
-- that uses it, from report/:
--   npx wrangler d1 execute crosstune-reports --remote --file limits.sql
-- Running it again changes nothing but the counts, which it takes again from the table.

-- When each report came in, in order: the daily clear-out finds the old ones here, and the counts start from it.
CREATE INDEX IF NOT EXISTS reports_at ON reports (at);

-- How many reports the table holds, under 'all', and how many came in each day, under the day ('2026-11-11', UTC).
-- The triggers keep them exact as reports come and go, by hand too, so the Worker reads two rows, never counts.
CREATE TABLE IF NOT EXISTS report_counts (key TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;

CREATE TRIGGER IF NOT EXISTS report_counted AFTER INSERT ON reports BEGIN
  INSERT INTO report_counts (key, n) VALUES ('all', 1), (date(NEW.at), 1) ON CONFLICT (key) DO UPDATE SET n = n + 1;
END;

CREATE TRIGGER IF NOT EXISTS report_uncounted AFTER DELETE ON reports BEGIN
  UPDATE report_counts SET n = n - 1 WHERE key IN ('all', date(OLD.at));
END;

-- Taken again from the table: the reports it holds, and today's and yesterday's, the only days the Worker asks.
DELETE FROM report_counts;
INSERT INTO report_counts (key, n) SELECT 'all', count(*) FROM reports;
INSERT INTO report_counts (key, n) SELECT date(at), count(*) FROM reports WHERE at >= date('now', '-1 day') GROUP BY date(at);

-- How many reports a network sent today, under a key that is a keyed hash (HMAC-SHA256) of the day, the kind of
-- report and the network's address prefix, with a secret only the Worker holds (NETWORK_KEY). The key can't be
-- turned back into the network, it is another one each day, and the daily clear-out deletes past days.
CREATE TABLE IF NOT EXISTS network_days (key TEXT PRIMARY KEY, day TEXT NOT NULL, n INTEGER NOT NULL) WITHOUT ROWID;

-- How many reports were turned away each day ('2026-11-11', UTC), and why: 'network' (its network sent its day's
-- reports), 'day_full' (the day's reports are all in), 'kept_full' (the table holds all it may), 'no_key' (the
-- Worker has no NETWORK_KEY). reports.py's triage shows them. Counted up to 10000 a day for each, so a flood
-- costs a write a refusal only until then.
CREATE TABLE IF NOT EXISTS refusals (day TEXT NOT NULL, why TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, why)) WITHOUT ROWID;
