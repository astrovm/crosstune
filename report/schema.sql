-- The problems reported from Crosstune, read by hand. No IP address or anything else about who sent it.
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY,
  at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version TEXT NOT NULL,
  android TEXT,
  title TEXT,
  artist TEXT,
  app TEXT,
  timed INTEGER,
  synced INTEGER,
  sung INTEGER,
  length_ms INTEGER,
  -- Where the words came from, and their timing when it was borrowed: "letras, timed by lrclib".
  -- Added after: ALTER TABLE reports ADD COLUMN source TEXT;
  source TEXT,
  -- JSON: each line put in time by hand, where the words had it and where it's heard.
  timing TEXT,
  comment TEXT,
  -- What the app found wrong by itself, when it sent the report rather than the user.
  -- Added after the first deploy: ALTER TABLE reports ADD COLUMN problem TEXT;
  problem TEXT
);
