# Grading a batch

Each song in a `batch-NN.json` has the lyrics Crosstune found, and some of what it made of them: a
translation, readings for words not in Latin letters, chords. Grade each part from what's there and
what you know of the song. Leave a part out when the song doesn't have it. The translation, readings
and chords only hold the song's first 40 lines, to keep batches short: judge those lines, and never
flag the rest as missing.

- **lyrics**: they're this song's words, all of them, in the song's own language. Nothing else in
  them: no credits ("Written by", "作词"), notes ("[Chorus]" is fine), ads, or lines from another song.
- **translation**: each line means what the original says, reads naturally, and none is left
  untranslated when it isn't in the target language already. Judge meaning, not style.
- **readings**: each kana reading or romanization is how the line is sung. Flag wrong readings of
  kanji (行く read こう), wrong romaji, and Chinese read as Japanese or the other way round. Hindi,
  Punjabi, Tamil, Thai, Arabic, Hebrew, Greek or Russian in Latin letters should read close to how
  it's sung: flag letters that are dropped or mangled, not a spelling you'd have chosen.
- **chords**: the song's lines as the app shows them, each chord in brackets where it's played,
  after any played before the line: "[G]Walking [C]down". They look like a real song's chords, in
  places that make sense, and the sheet (its `url`) is of this song. Flag text in brackets that
  isn't a chord name: start `why` with "site text:" and quote it.

Grade the whole batch and write the file without stopping to ask: when in doubt, it's ok.

Write `grades-NN.json` next to the batch, a list with one entry per song:

```json
[{"id": "123", "lyrics": {"ok": true}, "translation": {"ok": false, "why": "line 4 says the opposite: 'no me voy' as 'I'm going'"}}]
```

`why` names the line and what's wrong, in one sentence. Mark `ok: false` only for a mistake a
listener would notice, not a word you'd have put differently.
