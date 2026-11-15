-- Each report the app keeps to send carries its own id, a random UUID, so one sent again, as it got here but the
-- answer didn't get back, is kept once (worker.js, REPORT_ID). Run on the live database before deploying the Worker
-- that fills it, from report/, then run limits.sql again, so its triggers count the new column's bytes:
--   npx wrangler d1 execute crosstune-reports --remote --file migrations/0001_report_id.sql
--   npx wrangler d1 execute crosstune-reports --remote --file limits.sql
-- Reports from before, and those sent without an id, stay NULL there, and out of the index.
-- Run it once. Run again, it stops at once with "duplicate column name: report_id": that says it ran already, and
-- changes nothing.
ALTER TABLE reports ADD COLUMN report_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS reports_report_id ON reports (report_id) WHERE report_id IS NOT NULL;
