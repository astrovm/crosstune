# Opening a second song: Issue #678

## Inputs and method

The Samsung SM-S908E is selected by model, with its shared lock held per pair.
`second_song.py` saves and restores Crosstune's preferences. It reads Android's
activity tasks and the visible accessibility tree and takes a screenshot for each
song. `--keep-playing` leaves the destination running between the first and second
song; cleanup stops playback after the pair. By default, media volume must be zero. A daytime `--allow-sound` run preserves
the current volume. Each pair closes its automation session before releasing the lock. It never accepts a trial, signs up, or enables automatic reports.

Search pair: **Crosstune Unreleased First — Test Band**, then
**Crosstune Unreleased Second — Test Band**. These synthetic names exercise the
fallback search rather than an exact song page.

Music-link pair: **Harder, Better, Faster, Stronger — Daft Punk**
(`https://www.deezer.com/track/3135556`), then **Blinding Lights — The Weeknd**
(`https://www.deezer.com/track/908604612`). For Deezer itself, use Spotify links
for **Never Gonna Give You Up — Rick Astley**
(`https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT`), then **Blinding Lights**
(`https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b`), avoiding a share back to
its source app. Both Spotify titles were verified with Spotify's oEmbed endpoint.

## Baseline measured before the fix

These observations were collected before the worktree move. The raw build-folder
outputs did not survive the move; the TIDAL screenshots are retained in the PR.
The overnight rule required force-stopping any app that began playback, so the
old link runs do not prove warm reuse in those apps. The latest run must leave
apps running between songs.

| Service | How | First requested | Second requested | Before result |
| --- | --- | --- | --- | --- |
| TIDAL | Card search and direct fallback | Unreleased First | Unreleased Second | Wrong: search field retained First; settled in TIDAL's own task. |
| TIDAL | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right: Discovery, then After Hours with the requested tracks. |
| Deezer | Card search | Unreleased First | Unreleased Second | Not testable: NativePremiumActivity in Deezer's task says a paid plan is required in this country. |
| Deezer | Music links | Harder, Better, Faster, Stronger | Blinding Lights | Card reaches the plan screen; sharing a Deezer link back to Deezer can stay on Crosstune's card. Latest run uses Spotify source links. |
| Apple Music | Search | Unreleased First | Unreleased Second | Empty field; its declared MEDIA_SEARCH handler reaches sign-in. Not testable without signing in. |
| Apple Music | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right catalog pages in Apple Music's task. |
| Amazon Music | Search | Unreleased First | Unreleased Second | Right second query in Amazon's task; one initial card run showed an Unlimited offer. No offer accepted. |
| Amazon Music | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right second query in Amazon's task. |
| Qobuz | Search | Unreleased First | Unreleased Second | Empty search field in Qobuz's task. Its public app search link does not carry the query. This does not verify either requested search. |
| Qobuz | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right album and now-playing title before the required force-stop. |
| Audiomack | Search | Unreleased First | Unreleased Second | Right first and second query. |
| Audiomack | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right first and second query, including fallback results. |
| Spotify | Search | Unreleased First | Unreleased Second | An initial Continue prompt; second query was visible. |
| Spotify | Music links | Harder, Better, Faster, Stronger | Blinding Lights | Started playback and was force-stopped; no warm-reuse claim from this baseline. |
| Bandcamp | Search | Unreleased First | Unreleased Second | Right first and second query. |
| Bandcamp | Card and shared music link | Harder, Better, Faster, Stronger | Blinding Lights | Right first and second query. |
| YouTube | Search | Unreleased First | Unreleased Second | Right first and second query. |
| YouTube | Music links | Harder, Better, Faster, Stronger | Blinding Lights | Started playback and was force-stopped; no warm-reuse claim from this baseline. |
| YouTube Music, official | Search | Unreleased First | Unreleased Second | Right queries in com.google.android.apps.youtube.music. |
| YouTube Music, Morphe | Search | Unreleased First | Unreleased Second | Right queries in app.morphe.android.apps.youtube.music; action label is YT Music Morphe. |
| Both YouTube Music builds | Music links | Harder, Better, Faster, Stronger | Blinding Lights | Started playback and were force-stopped; no warm-reuse claim from this baseline. |
| SoundCloud | Both | Both pairs | Both pairs | Not testable: app is not installed. |

## Regression evidence

The original Compose song-card regression failed because Spotify's launch lacked
NEW_TASK. The Playback regression failed because Qobuz's forwarded intent lacked
NEW_TASK. The TIDAL Compose and Playback regressions then failed because the app
was opened directly on its stale search rather than leaving that screen first.
All 88 targeted tests passed after the fix.

On the pre-restart fixed APK, the Samsung card pair displayed First, then Second,
instead of retaining First. The PR includes the before/after screenshots. CI
passed tests, coverage, lint, builds and CodeQL on 733a4b8b. Mutation testing caught
22 of 25 changes; the three surviving changes prompted cross-service cancellation
and silent missing-app regression checks before final verification.

## Latest verification

The current per-service table, commit, CI, local checks and mutation results are
maintained in [PR #729](https://github.com/astrovm/crosstune/pull/729).

After `device_open` supplies the device-bound CLI, the daytime confirmation is:

```bash
python3 scripts/emulator/second_song.py after --device-cli "$DEVICE_CLI" \
  --keep-playing --allow-sound --routes primary --output build/second-song/after
```

`primary` exercises two songs from a card search and two from directly shared
music links for every installed service. The full four-route matrix is the default;
use it for TIDAL as well to confirm direct fallback and exact-link card launches.
The output contains task dumps, visible nodes and screenshots, never saved account
credentials or a phone address. Before handing the phone back, verify no playback,
media volume 5 and Do Not Disturb off, then call `device_close`.
