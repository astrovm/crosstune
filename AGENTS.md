# Crosstune

Crosstune is an Android app that opens a music link in the service you prefer, then helps you learn the song: lyrics, translations, practice cards. One Kotlin + Compose module, package `com.astrovm.crosstune`, flat in `app/src/main/java/com/astrovm/crosstune/`.

## Commands

```bash
git submodule update --init --recursive   # projectM is built from source for the visuals
./gradlew :app:assembleDev                # "Crosstune Dev" (com.astrovm.crosstune.dev), installs next to the real app
./gradlew :app:testDebugUnitTest --tests '*MainActivityTest.aSongPlays*'   # one test
./gradlew :app:ci                         # all tests + 100% line coverage + lint + APKs
python3 scripts/build-site.py             # after changing site/; commit docs/ too
```

Results land in `app/build/test-results/testDebugUnitTest/*.xml`, coverage in `app/build/reports/kover/reportDebug.xml`.

## Hit every surface

The most common defect here is a change that works where you tested it and is missing elsewhere. Before calling it done, check:

- **Strings.** Every user-visible string goes in all 16 locales: `values` plus `de es fr hi in it ja ko nl pl pt ru tr zh-rCN zh-rTW`. `TranslationsTest` fails otherwise. Spanish uses Argentine voseo. Escape apostrophes as `\'`.
- **Docs.** README's feature list, `site/strings/` and the store text in `fastlane/` describe what the app does. Keep them matching, in every language.

## Design

- Only the text the user needs. Icons, color and state before words.
- Show what's happening, not what isn't. Errors show when they happen, never "No errors".
- The site keeps its six cards. Fold new things into an existing one.
- Check a new screen's every state on a phone: loading, empty, error, the largest font, and a long language like German.
- Touch targets of at least 48dp, and a content description on every icon.

## How it works

`MainActivity` builds `MainViewModel` and hands its methods to the screens as `ScreenActions`. The view model owns one immutable `UiState` and replaces it with `copy(...)`. Screens never call services. `CrosstuneScreen.kt` picks the screen from `UiState` in a `when`, and the order matters (lyrics over player over the main card).

Playback follows and controls other music apps through Android's media sessions (`Playback.kt`). Visuals run projectM through JNI on a GL thread.

## Verifying

- Smallest proof first: the test you touched, not the whole suite. CI owns `:app:ci`.
- Kover requires 100% line coverage. Only code that needs a real device is excluded (list in `app/build.gradle.kts`). Every new line needs a test that runs it.
- Test behavior through Compose in `MainActivityTest` and friends, with the fakes behind `MainActivity`'s `@VisibleForTesting` factories (`FakePlayback`, `FakeSpotify`, ...).
- Tapping a disabled Compose node does nothing: wait for `isEnabled()` first.

## Pull requests

- UI changes need before/after screenshots in the PR description. Never commit them.
- One problem per PR.

## Taste

- Copy and comments are plain, conversational English. Comments say why, in the app's own terms.
- Simplest thing that works. Don't add machinery for cases that don't exist.
