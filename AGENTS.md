# Crosstune

Crosstune is an Android app that opens a music link in the service you prefer, then helps you learn the song: lyrics, translations, practice cards. One Kotlin + Compose module, package `com.astrovm.crosstune`, flat in `app/src/main/java/com/astrovm/crosstune/`. README's feature list is what the app does; building, releasing, the site and the screenshots are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Commands

```bash
git submodule update --init --recursive   # projectM is built from source for the visuals
./gradlew :app:assembleDebug              # "Crosstune Dev" (com.astrovm.crosstune.dev), installs next to the real app; what Android Studio runs for Live Edit. The `play` build (the default); `foss` has no Google code, see Casting below
./gradlew :app:testPlayDebugUnitTest --tests '*MainActivityTest.aSongPlays*'   # one test
./gradlew :app:ci                         # all tests + coverage gates + lint + detekt + APKs
python3 scripts/build-site.py             # after changing site/; commit docs/ too
python3 scripts/mutants.py                # mutation testing on the lines this branch changed; slow, so only when asked, in the background
python3 scripts/shots/shots.py            # store screenshots and site clips, through :app:shots; no phone needed
python3 scripts/syncreplay/syncreplay.py run ID   # the lyrics timing replayed on a real recording, line by line against when it's sung; prep first, see the script
python3 scripts/syncreplay/syncreplay.py phone ID --seconds S   # the same on the phone over adb, timed from the dev app's own log; screenshots time lines up to 2 s off
node --test report/*.test.mjs             # the report Worker's tests
```

Results land in `app/build/test-results/testPlayDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportPlayDebug.xml`.

Live Edit (Android Studio) is for tweaks inside composable bodies: edit the file while it's open in Studio and it reaches the dev app on a phone in seconds. Strings, `UiState`, new functions and signature changes need a real build: run the app from Studio's Run button, which keeps Live Edit connected. It doesn't replace the order under Verifying.

## Hit every surface

The most common defect here is a change that works where you tested it and is missing elsewhere. Before calling it done, check:

- **Strings.** Every user-visible string goes in all 16 locales: `values` plus `de es fr hi in it ja ko nl pl pt ru tr zh-rCN zh-rTW`. `TranslationsTest` fails otherwise. Spanish uses Argentine voseo. Escape apostrophes as `\'`.
- **Shortcuts.** `app/src/debug/` keeps a copy of `res/xml/shortcuts.xml` pointing at its own package. Change both together.
- **Docs.** README's feature list, `site/strings/` and the store text in `fastlane/` describe what the app does. Keep them matching, in every language.
- **Privacy.** Anything that changes what leaves the phone, or where it goes, changes README's Privacy section and the privacy page (`privacy_body` in every `site/strings/` file) too.
- **Version.** A bump goes in the PR being released, with its notes in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. CI fails without them (`scripts/release/check_changelog.py`).

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
- Android 8 (minSdk 26) and up. An API added later needs a version check; easy to miss in plain Java calls like `readNBytes`.

## How it works

`MainActivity` builds `MainViewModel` and hands its methods to the screens as `ScreenActions`. The view model owns one immutable `UiState` and replaces it with `copy(...)`. Screens never call services. `CrosstuneScreen.kt` picks the screen from `UiState` in a `when`, and the order matters (lyrics over player over the main card).

Playback follows and controls other music apps through Android's media sessions (`Playback.kt`). Visuals run projectM through JNI on a GL thread.

Where each feature starts:

- Links: `MusicLinks.kt` reads them, `LinkResolver.kt` names the song, `Destinations.kt` and `Frontend.kt` pick where it opens.
- Naming a song: `SongListener.kt`, `ShazamSignature.kt`, `SongRecognizers.kt`.
- Lyrics: `LyricsFinder.kt` finds them, `LyricsScreen.kt` shows them, `TimingEvidence.kt` and `LyricsSync.kt` put them in time, `SungWords.kt` lines them up to the singing.
- Chords: `ChordFinder.kt`, `Chords.kt`. Translations and readings: `Translator.kt`, `Readings.kt`. ChatGPT: `ChatGpt*.kt`.
- Practice cards: `Practice*.kt`, `SavedLines.kt`.
- Casting to a TV: `Cast.kt` is the protocol, `CastSender`, `CastReceiver` and `CastPlayback`; Google's part is `GoogleCast*.kt` in `app/src/play/` only, and the `foss` flavor has none of it. See Casting in `CONTRIBUTING.md`.
- Access and setup: `FixCenter.kt`, `OneTapSetup.kt` (Shizuku and root).
- Reports: `Feedback.kt` and `AutoReports.kt` in the app, `report/` on Cloudflare.
- TV: `UiState.phone` is `Phone.TV` (`FixCenter.kt`), and the screens check it. Tests in `TvTest` and `TvPlayTest`.

## Verifying

- Smallest proof first: the test you touched, not the whole suite. CI owns `:app:ci`.
- Order, every time: 1) push, so the PR's CI starts; 2) the phone gets the app: when the user is testing with Live Edit, deploy from Studio and skip `assembleDebug`; otherwise `./gradlew :app:assembleDebug` alone, then install the APK; 3) `./gradlew :app:ci -x :app:assembleRelease` in the background. Install nothing on the POCO unless asked. Never put the build and the tests in one Gradle command: it runs the tests first, and one failing test means no APK.
- Kover requires 100% line coverage. Only code that needs a real device is excluded (list in `app/build.gradle.kts`). Every new line needs a test that runs it. Branch coverage only has to stay at 88% or more.
- `./gradlew :app:detekt` fails on new tangled logic: too many paths in a function, a condition with too many parts, blocks nested too deep. Split it up. `config/detekt/baseline.xml` lists what was already there; never add to it.
- Mutation testing never blocks a PR. The **Mutants** workflow runs it on each PR that changes the app's code, outside `CI / Required`, and comments the changes no test caught. The ones that can change what the app does get tests in their own PR. Locally, run `python3 scripts/mutants.py` only when asked.
- Test behavior through Compose in `MainActivityTest` and friends, with the fakes behind `MainActivity`'s `@VisibleForTesting` factories (`FakePlayback`, `FakeSpotify`, ...).
- Tapping a disabled Compose node does nothing: wait for `isEnabled()` first.
- The fakes answer at once, on `Dispatchers.Unconfined`, so loading states and late answers never show. To test one, hold the fake's answer (a `CountDownLatch` in its handler) until the test has looked.
- When the focused node goes away, focus can land anywhere. Assert focus on something that stays.
- Kover never sees an interface's default bodies run, as Kotlin calls them another way. Keep interface members abstract.

## Pull requests

- UI changes need before/after screenshots in the PR description. Never commit them.
- One problem per PR. A bug found along the way gets its own PR, not a skip.

## Taste

- Copy and comments are plain, conversational English. Comments say why, in the app's own terms.
- Simplest design that works. That means the simplest design, not the smallest patch. Don't add machinery for cases that don't exist.
