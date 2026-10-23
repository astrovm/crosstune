# Crosstune

Crosstune is an Android app that opens a music link in the service you prefer, then helps you learn the song: lyrics, translations, practice cards. One Kotlin + Compose module, package `com.astrovm.crosstune`, flat in `app/src/main/java/com/astrovm/crosstune/`.

## Commands

```bash
git submodule update --init --recursive   # projectM is built from source for the visuals
./gradlew :app:assembleDebug              # "Crosstune Dev" (com.astrovm.crosstune.dev), installs next to the real app; what Android Studio runs for Live Edit
./gradlew :app:testDebugUnitTest --tests '*MainActivityTest.aSongPlays*'   # one test
./gradlew :app:ci                         # all tests + 100% line coverage + lint + APKs
python3 scripts/build-site.py             # after changing site/; commit docs/ too
python3 scripts/mutants.py                # mutation testing on the lines this branch changed; slow, so in the background
```

Results land in `app/build/test-results/testDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportDebug.xml`.

Live Edit (Android Studio) is for tweaks inside composable bodies: edit the file while it's open in Studio and it reaches the dev app on a phone in seconds. Strings, `UiState`, new functions and signature changes need a real build: run the app from Studio's Run button, which keeps Live Edit connected. It doesn't replace the order under Verifying.

## Hit every surface

The most common defect here is a change that works where you tested it and is missing elsewhere. Before calling it done, check:

- **Strings.** Every user-visible string goes in all 16 locales: `values` plus `de es fr hi in it ja ko nl pl pt ru tr zh-rCN zh-rTW`. `TranslationsTest` fails otherwise. Spanish uses Argentine voseo. Escape apostrophes as `\'`.
- **Shortcuts.** `app/src/debug/` keeps a copy of `res/xml/shortcuts.xml` pointing at its own package. Change both together.
- **Docs.** README's feature list, `site/strings/` and the store text in `fastlane/` describe what the app does. Keep them matching, in every language.

## Design

- Crosstune needs three things from Android to work: opening links, notification access and the microphone. Missing any is a problem to show and fix, never an extra.
- Design for someone without Shizuku, root or ADB first. Shizuku only adds a shortcut on top.
- The content is the screen. Nothing covers the words, the cover art or the visuals, and a screen's tools stay small, like the lyrics pill.
- Words cost. Icon, color and state before text. A label, hint or caption has to earn its place. Problems are the exception: say them plainly, in words.
- Show state by how it looks, not with a caption. Show what's wrong, never that things are fine: no "No errors".
- Fold a feature into the thing it belongs to. Naming a song is the mic in the link field, not a button. What's playing is a bar at the bottom, not a caption.
- The main action is a filled button with an icon and one word (**Play**, **Open**). Lesser tools are flat, with no tile or box: an icon with at most a short label.
- Icon-only is fine with a content description and a touch target of at least 48dp.
- Check every state on a phone: loading, empty, error, missing access, no Shizuku, the largest font, and a long language like German.

## How it works

`MainActivity` builds `MainViewModel` and hands its methods to the screens as `ScreenActions`. The view model owns one immutable `UiState` and replaces it with `copy(...)`. Screens never call services. `CrosstuneScreen.kt` picks the screen from `UiState` in a `when`, and the order matters (lyrics over player over the main card).

Playback follows and controls other music apps through Android's media sessions (`Playback.kt`). Visuals run projectM through JNI on a GL thread.

## Verifying

- Smallest proof first: the test you touched, not the whole suite. CI owns `:app:ci`.
- Order, every time: 1) push, so the PR's CI starts; 2) the phone gets the app: when the user is testing with Live Edit, deploy from Studio and skip `assembleDebug`; otherwise `./gradlew :app:assembleDebug` alone, then install the APK; 3) `./gradlew :app:ci -x :app:assembleRelease` in the background. Install nothing on the POCO unless asked. Never put the build and the tests in one Gradle command: it runs the tests first, and one failing test means no APK.
- Kover requires 100% line coverage. Only code that needs a real device is excluded (list in `app/build.gradle.kts`). Every new line needs a test that runs it.
- Changed logic gets mutation testing too: `python3 scripts/mutants.py`. Each change it lists that lived is a test missing, unless it can't change what the app does.
- Test behavior through Compose in `MainActivityTest` and friends, with the fakes behind `MainActivity`'s `@VisibleForTesting` factories (`FakePlayback`, `FakeSpotify`, ...).
- Tapping a disabled Compose node does nothing: wait for `isEnabled()` first.

## Pull requests

- UI changes need before/after screenshots in the PR description. Never commit them.
- One problem per PR.

## Taste

- Copy and comments are plain, conversational English. Comments say why, in the app's own terms.
- Simplest thing that works. Don't add machinery for cases that don't exist.
