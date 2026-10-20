# AGENTS.md

Guidance for coding agents working in this repository.

## Commands

```bash
git submodule update --init --recursive   # projectM (third_party/projectm) is built from source for the visuals
./gradlew :app:assembleDev                # "Crosstune Dev" (com.astrovm.crosstune.dev), minified, debug-signed, installs next to the real app
./gradlew :app:ci                         # unit tests + 100% line coverage gate + lint + debug and release APKs
./gradlew :app:testDebugUnitTest --tests '*MainActivityTest.aSongPlays*'   # one test
python3 scripts/release/check_changelog.py   # CI also requires the current version's English changelog
python3 scripts/build-site.py                # after changing site/; commit docs/ too (CI diffs it)
python3 scripts/shots/shots.py [ja ko]       # store screenshots and site clips, drawn with Robolectric
```

- Tests are Robolectric JVM tests in `app/src/test`; there are no instrumented tests besides `baselineprofile/`. Results land in `app/build/test-results/testDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportDebug.xml`.
- Kover requires 100% line coverage of `debug`. Only code that needs a real device is excluded, in `app/build.gradle.kts` (`NativeMilkdrop*`, `GlThread`, `MilkdropView*`, `ShizukuShell*`, `MediaBrowserWaker*`). Every new line needs a test that runs it; an unreachable default or branch fails CI.
- `shots.*` tests only run through the `shots` task, not `testDebugUnitTest`.
- Releasing: bump `versionName`/`versionCode` (`X*1000000 + Y*10000 + Z*100`) in `app/build.gradle.kts` and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, then tag `vX.Y.Z`. The release APK must stay reproducible (F-Droid compares it), which `scripts/release/check_native_paths.py` guards.

## Architecture

Single-module Kotlin + Jetpack Compose app, package `com.astrovm.crosstune`, all in `app/src/main/java/com/astrovm/crosstune/` (flat, no sub-packages besides `ui/theme`).

- **State flow:** `MainActivity` builds `MainViewModel` and passes its methods into `ScreenActions` (a data class of lambdas, `CrosstuneScreen.kt`). The view model owns one immutable `UiState` (`UiState.kt`) and replaces it with `copy(...)`. Screens never call services directly.
- **Screens:** `CrosstuneScreen.kt` picks the screen from `UiState` in a `when` (order matters: lyrics over player over the main card, and so on) and animates between them by `Screen.depth`. Bigger screens live in their own files (`LyricsScreen`, `PlayerScreen`, `PracticeScreen`, `SettingsScreen`, `SetupScreen`, `ListeningScreen`).
- **Links → song → destination:** `MusicLinks`/`TrackingLinks` parse and clean incoming links, `LinkResolver` reads title and artist from the source service (key-less endpoints), `ExactMatcher` and `ServiceApis` find the same item on the destination, and `Destinations`/`MusicService`/`Frontend` describe where it can open (built-in services, alternative frontends, custom search templates). `LookupCache` and `HistoryStore` persist results.
- **Playback:** `Playback.kt` (`PlaybackSource`, real one `MediaSessionPlayback`) follows and controls other music apps through Android's media sessions (needs notification-listener access). `AppWaker` wakes an app that isn't running so it can be asked to play in the background.
- **Lyrics:** `LyricsFinder` (LRCLIB, YouTube Music, NetEase, Letras) plus `LyricsSync`, `SyncedLyrics`, `BorrowedTimings` for timing; `Readings`/`SungReadings` (Kuromoji) for Japanese; `Translator`, `ChatGptTutor`/`ChatGpt` for translation and explanations; `Chords`/`ChordFinder`; `FloatingLyricsService` for the floating window.
- **Listening:** `SongListener` + `ShazamSignature` fingerprint microphone audio for Shazam; `SongRecognizers` hands off to other recognizer apps.
- **Visuals:** `Milkdrop.kt` drives projectM through JNI (`app/src/main/cpp/milkdrop.cpp`, built by CMake for arm64-v8a, armeabi-v7a and x86_64) on a GL thread. Presets, textures and thumbnails are in `app/src/main/assets/milkdrop*`; List each new preset under its kind in `milkdrop-kinds.txt`, and redraw thumbnails with `scripts/milkdrop-render/thumbs.py`.
- **Practice:** `SavedLines`, `Practice`, `PracticeList`, `PracticeScreen` keep lines to learn, with spaced repetition.
- **System integration:** `OneTapSetup` + `ShizukuShell` change Android settings through Shizuku; `LinkInterception` reads which apps open which music links, for setup; widgets (`CrosstuneWidget`, Glance), quick-settings tiles (`*TileService`), `AppShortcuts`.

## Testing seams

- `MainActivity`'s companion object holds `@VisibleForTesting` factories (`httpClientFactory`, `playbackFactory`, `microphoneFactory`, `milkdropFactory`, `hearingFactory`, and others). Tests swap them for fakes in `app/src/test`: `FakeSpotify` (an HTTP interceptor; set `fake.handler`), `FakePlayback`, `FakeMicrophone`, `FakeShell`, `FakeOpenAi`, `InstalledApp`.
- Most behavior is tested end to end through Compose in `MainActivityTest`, `ListeningTest`, `FlashcardsTest` and friends, driving the real UI by text and content description.
- Compose: tapping a disabled node does nothing, so wait for `isEnabled()` before clicking. `MainActivityTest.waitUntil` re-evaluates its condition after the loop, so conditions must not have side effects (peek at started activities, don't pop them).

## Conventions

- Every user-visible string goes in all 16 locales: `values` plus `de es fr hi in it ja ko nl pl pt ru tr zh-rCN zh-rTW`. `TranslationsTest` fails otherwise. Spanish uses Argentine voseo; escape apostrophes as `\'`.
- Copy and comments are plain, conversational English. KDoc says what something is and why, in the app's own terms (see the existing files), not how.
- README's feature list and `site/` describe what the app does; keep them matching behavior changes.
- The `dev` build type is the one to install on phones for manual testing.
