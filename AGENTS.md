# Crosstune

Crosstune is an Android app that opens a music link in the service you prefer, then helps you learn the song: lyrics, translations, practice cards. One Kotlin + Compose module, package `com.astrovm.crosstune`, flat in `app/src/main/java/com/astrovm/crosstune/`.

## Commands

```bash
git submodule update --init --recursive   # projectM is built from source for the visuals
./gradlew :app:assembleDev                # "Crosstune Dev" (com.astrovm.crosstune.dev), installs next to the real app
./gradlew :app:assembleDebug              # "Crosstune Debug" (com.astrovm.crosstune.debug), what Android Studio runs for Live Edit
./gradlew :app:testDebugUnitTest --tests '*MainActivityTest.aSongPlays*'   # one test
./gradlew :app:ci                         # all tests + 100% line coverage + lint + APKs
python3 scripts/build-site.py             # after changing site/; commit docs/ too
```

Results land in `app/build/test-results/testDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportDebug.xml`.

Live Edit (Android Studio) is for tweaks inside composable bodies: edit the file while it's open in Studio and it reaches a debug build on a phone in seconds. Strings, `UiState`, new functions and signature changes need a real build: run the debug app from Studio's Run button, which keeps Live Edit connected. It doesn't replace the order under Verifying.

## Hit every surface

The most common defect here is a change that works where you tested it and is missing elsewhere. Before calling it done, check:

- **Strings.** Every user-visible string goes in all 16 locales: `values` plus `de es fr hi in it ja ko nl pl pt ru tr zh-rCN zh-rTW`. `TranslationsTest` fails otherwise. Spanish uses Argentine voseo. Escape apostrophes as `\'`.
- **Shortcuts.** `app/src/dev/` and `app/src/debug/` each keep a copy of `res/xml/shortcuts.xml` pointing at their own package. Change all three together.
- **Docs.** README's feature list, `site/strings/` and the store text in `fastlane/` describe what the app does. Keep them matching, in every language.

## Design

- The content is the screen. Controls never cover it, and a screen's tools stay small, like the lyrics pill.
- Words cost. Icon, color and state before text. A label, hint or caption has to earn its place.
- Show state by how it looks, not with a caption. Show what's happening, never what isn't: errors when they happen, never "No errors".
- Adding is the last resort. Fold new things into what's there (the site keeps its six cards). A redesign that ends with more buttons or more text than it started with has failed.
- Fold a feature into the thing it belongs to. Naming a song is the mic in the link field, not a button. What's playing is a bar at the bottom, not a caption.
- The main action is a filled button with an icon and one word (**Play**, **Open**). Lesser tools are flat, with no tile or box: an icon with at most a short label.
- Before redesigning a screen, list what's on it and what each thing is for. Count again after.
- Icon-only is fine with a content description and a touch target of at least 48dp.
- Check every state on a phone: loading, empty, error, the largest font, and a long language like German.

## How it works

`MainActivity` builds `MainViewModel` and hands its methods to the screens as `ScreenActions`. The view model owns one immutable `UiState` and replaces it with `copy(...)`. Screens never call services. `CrosstuneScreen.kt` picks the screen from `UiState` in a `when`, and the order matters (lyrics over player over the main card).

Playback follows and controls other music apps through Android's media sessions (`Playback.kt`). Visuals run projectM through JNI on a GL thread.

## Verifying

- Smallest proof first: the test you touched, not the whole suite. CI owns `:app:ci`.
- Order, every time: 1) push, so the PR's CI starts; 2) the phone gets the app: when the user is testing with the debug build and Live Edit, deploy that from Studio and skip `assembleDev`; otherwise `./gradlew :app:assembleDev` alone, then install the APK; 3) `./gradlew :app:ci -x :app:assembleRelease -x :app:assembleDev` in the background. Install nothing on the POCO unless asked. Never put the build and the tests in one Gradle command: it runs the tests first, and one failing test means no APK.
- Kover requires 100% line coverage. Only code that needs a real device is excluded (list in `app/build.gradle.kts`). Every new line needs a test that runs it.
- Test behavior through Compose in `MainActivityTest` and friends, with the fakes behind `MainActivity`'s `@VisibleForTesting` factories (`FakePlayback`, `FakeSpotify`, ...).
- Tapping a disabled Compose node does nothing: wait for `isEnabled()` first.

## Pull requests

- UI changes need before/after screenshots in the PR description. Never commit them.
- One problem per PR.

## Taste

- Copy and comments are plain, conversational English. Comments say why, in the app's own terms.
- Simplest thing that works. Don't add machinery for cases that don't exist.
