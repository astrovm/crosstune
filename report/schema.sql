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
  timing_log TEXT,
  -- Which recording a timing kept from an earlier version was for, when the app knew: "SPOTIFY:id".
  -- Added after, before the Worker that fills it is deployed: ALTER TABLE reports ADD COLUMN recording TEXT;
  recording TEXT,
  -- A reading the user marked wrong, on the word itself (problem "wrong_reading"). Added after, in this
  -- order, before the Worker that fills them is deployed (other reports send none, so they stay NULL):
  --   ALTER TABLE reports ADD COLUMN lyric_line TEXT;
  --   ALTER TABLE reports ADD COLUMN word TEXT;
  --   ALTER TABLE reports ADD COLUMN reading_shown TEXT;
  --   ALTER TABLE reports ADD COLUMN reader TEXT;
  --   ALTER TABLE reports ADD COLUMN language TEXT;
  --   ALTER TABLE reports ADD COLUMN right_reading TEXT;
  -- The line the word is in, as the lyrics write it.
  lyric_line TEXT,
  -- The word as written: "君".
  word TEXT,
  -- How the word's details read it, each way it shows, " / " between: "くん / kun".
  reading_shown TEXT,
  -- What read it, "+" between when more than one did: "ipadic", "unidic", "crosstune_rules", "lyrics_ruby",
  -- "utaten", "chatgpt", "android_pinyin", "crosstune_romaja", "android_latin".
  reader TEXT,
  -- The script it was read in: "japanese", "chinese", "korean", "cyrillic", and so on.
  language TEXT,
  -- The right reading, when the user typed it.
  right_reading TEXT
);


-- The counts that keep this table from being filled, and their index, are in limits.sql: run it after this file.
