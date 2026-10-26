# Grading a batch

Each song in a `batch-NN.json` has the lyrics Crosstune found, and some of what it made of them: a
translation, readings for words not in Latin letters, chords. Grade each part from what's there and
what you know of the song. Leave a part out when the song doesn't have it.

- **lyrics**: they're this song's words, all of them, in the song's own language. Nothing else in
  them: no credits ("Written by", "作词"), notes ("[Chorus]" is fine), ads, or lines from another song.
- **translation**: each line means what the original says, reads naturally, and none is left
  untranslated when it isn't in the target language already. Judge meaning, not style.
- **readings**: each kana reading or romanization is how the line is sung. Flag wrong readings of
  kanji (行く read こう), wrong romaji, and Chinese read as Japanese or the other way round.
- **chords**: the sheet is of this song (its words match the lyrics), and the chords look like a
  real song's, not text read as chords.

Write `grades-NN.json` next to the batch, a list with one entry per song:

```json
[{"id": "123", "lyrics": {"ok": true}, "translation": {"ok": false, "why": "line 4 says the opposite: 'no me voy' as 'I'm going'"}}]
```

`why` names the line and what's wrong, in one sentence. Mark `ok: false` only for a mistake a
listener would notice, not a word you'd have put differently.
