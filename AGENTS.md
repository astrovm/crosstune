# Crosstune

Crosstune is an Android app that opens a music link in the service you prefer, then helps you learn the song: lyrics, translations, practice cards. One Kotlin + Compose module, package `com.astrovm.crosstune`, flat in `app/src/main/java/com/astrovm/crosstune/`. README's feature list is what the app does; building, testing, releasing, the site and the screenshots are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Commands

```bash
git submodule update --init --recursive   # projectM is built from source for the visuals
./gradlew :app:assembleDebug              # "Crosstune Dev" (com.astrovm.crosstune.dev), installs next to the real app
./gradlew :app:ci                         # all tests + coverage gates + lint + detekt + APKs
scripts/gradle-box/gradle-box.sh TASK...   # Gradle in a container, in turn with other checkouts; how agents and test runs build
python3 scripts/build-site.py             # after changing site/; commit docs/ too
python3 scripts/emulator/emulator.py check   # the test emulators, declared in scripts/emulator/emulators.json; `create` and `start` make and run them
python3 scripts/devicelock.py take samsung --who "task" --minutes 15   # shared lock for samsung or crosstune-phone; waits, writes who holds it, lets go when the time is up. `release` ends yours, `who` shows the holder and the queue
python3 scripts/syncreplay/syncreplay.py run ID   # the lyrics timing replayed on a real recording, line by line against when it's sung; prep first, see the script
ANDROID_SERIAL=SERIAL python3 scripts/syncreplay/syncreplay.py phone ID --seconds S   # the same on the phone over adb, timed from the dev app's own log; screenshots time lines up to 2 s off
python3 scripts/syncreplay/syncreplay.py run ID --like-phone    # the replay set up as that phone check went, held to its lines
node --test report/*.test.mjs             # the report Worker's tests
python3 scripts/reports/reports.py triage --since ID   # the reports in D1, grouped; `pull` first, read-only, and they stay out of git
```

Results land in `app/build/test-results/testDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportDebug.xml`. Every new line needs a test that runs it: Kover requires 100% line coverage.

## Hit every surface

The most common defect here is a change that works where you tested it and is missing elsewhere. Before calling it done, check:

- **Strings.** Spanish uses Argentine voseo.
- **Docs.** README's feature list, `site/strings/` and the store text in `fastlane/` describe what the app does. Keep them matching, in every language.
- **Privacy.** Anything that changes what leaves the phone, or where it goes, changes README's Privacy section and the privacy page (`privacy_body` in every `site/strings/` file) too.
- **Known issues.** A bug, limit or unchecked case you know of and aren't fixing in this PR gets a GitHub issue, linked from the PR. A note in a PR body or a comment alone gets lost.

## Design

- Crosstune needs two things from Android to work: opening links and notification access. Missing either is a problem to show and fix, never an extra. The microphone is asked where it's used: naming a song, or opening lyrics. With Shizuku, the one tap allows it too, since that costs nothing more.
- Design for someone without Shizuku, root or ADB first. Shizuku only adds a shortcut on top.
- The content is the screen. Nothing covers the words, the cover art or the visuals, and a screen's tools stay small, like the lyrics pill.
- Words cost. Icon, color and state before text. A label, hint or caption has to earn its place. Problems are the exception: say them plainly, in words.
- Show state by how it looks, not with a caption. Show what's wrong, never that things are fine: no "No errors".
- Fold a feature into the thing it belongs to. Naming a song is the mic in the link field, not a button. What's playing is a bar at the bottom, not a caption.
- The main action is a filled button with an icon and one word (**Play**, **Open**). Lesser tools are flat, with no tile or box: an icon with at most a short label.
- Icon-only is fine with a content description and a touch target of at least 48dp.
- Check every state on a phone: loading, empty, error, missing access, no Shizuku, offline, the largest font, a long language like German, and light, dark and pure black.
- A TV is a real target: everything works with a remote. Check where focus starts on each screen and dialog, and where it lands when one closes or its content changes.

## Principles

- Private by default: no account, analytics or ads. Sound is heard on the phone and never sent; naming a song sends Shazam a fingerprint only. The only server is the report Worker in `report/`.
- Data costs the user. Big downloads, like the 32 MB speech model, wait for an unmetered network unless the user asks for one.

## How it works

`MainActivity` builds `MainViewModel` and hands its methods to the screens as `ScreenActions`. The view model owns one immutable `UiState` and replaces it with `copy(...)`. Screens never call services. `CrosstuneScreen.kt` picks the screen from `UiState` in a `when`, and the order matters (lyrics over player over the main card).

Playback follows and controls other music apps through Android's media sessions (`Playback.kt`). Visuals run projectM through JNI on a GL thread.

Where each feature starts:

- Links: `MusicLinks.kt` reads them, `LinkResolver.kt` names the song, `Destinations.kt` and `Frontend.kt` pick where it opens.
- Naming a song: `SongListener.kt`, `ShazamSignature.kt`, `SongRecognizers.kt`.
- Lyrics: `LyricsFinder.kt` finds them, `LyricsScreen.kt` shows them, `TimingEvidence.kt` and `LyricsSync.kt` put them in time, `SungWords.kt` lines them up to the singing.
- Chords: `ChordFinder.kt`, `Chords.kt`. Translations and readings: `Translator.kt`, `Readings.kt`. ChatGPT: `ChatGpt*.kt`.
- Practice cards: `Practice*.kt`, `SavedLines.kt`.
- Access and setup: `FixCenter.kt`, `OneTapSetup.kt` (Shizuku and root).
- Reports: `Feedback.kt` and `AutoReports.kt` in the app, `report/` on Cloudflare.
- TV: `UiState.phone` is `Phone.TV` (`FixCenter.kt`), and the screens check it. Tests in `TvTest` and `TvPlayTest`.

## Testing

- Test behavior through Compose in `MainActivityTest` and friends, with the fakes behind `MainActivity`'s `@VisibleForTesting` factories (`FakePlayback`, `FakeSpotify`, ...).
- Tapping a disabled Compose node does nothing: wait for `isEnabled()` first.
- The fakes answer at once, on `Dispatchers.Unconfined`, so loading states and late answers never show. To test one, hold the fake's answer (a `CountDownLatch` in its handler) until the test has looked.
- When the focused node goes away, focus can land anywhere. Assert focus on something that stays.
- Kover never sees an interface's default bodies run, as Kotlin calls them another way. Keep interface members abstract.

## Taste

Copy and comments are plain, conversational English. Comments say why, in the app's own terms.
