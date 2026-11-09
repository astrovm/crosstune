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
  problem TEXT,
  -- What a report about the words' timing adds, to tell why they were off. Added after, in this order,
  -- before the Worker that fills them is deployed (old apps send none, so they stay NULL):
  --   ALTER TABLE reports ADD COLUMN app_package TEXT;
  --   ALTER TABLE reports ADD COLUMN speed REAL;
  --   ALTER TABLE reports ADD COLUMN lead_ms INTEGER;
  --   ALTER TABLE reports ADD COLUMN early_ms INTEGER;
  --   ALTER TABLE reports ADD COLUMN timing_log TEXT;
  -- The app followed, as Android names it: "com.spotify.music".
  app_package TEXT,
  -- How fast it said it played; 1.0 is the song's own speed.
  speed REAL,
  -- How far ahead of the song Crosstune took the app to be, when it knew.
  lead_ms INTEGER,
  -- How far ahead of the voice each line was lit (the Lines early setting).
  early_ms INTEGER,
  -- The last minute of Crosstune's timing log, one note a line: positions, speeds and times only.
  timing_log TEXT
);
