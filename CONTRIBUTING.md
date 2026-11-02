# Contributing

See [`AGENTS.md`](AGENTS.md) for how the code is laid out and checked.

## Building

Clone with `git clone --recurse-submodules`, or run `git submodule update --init --recursive` in a clone: the MilkDrop visuals build [projectM](https://github.com/projectM-visualizer/projectm) (LGPL 2.1) from `third_party/projectm`. Its presets in `app/src/main/assets/milkdrop/` come from projectM's [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop), [Milkdrop Original](https://github.com/projectM-visualizer/presets-milkdrop-original) and [En D](https://github.com/projectM-visualizer/presets-en-d) packs, by their authors in each file name. The images some of them draw with, in `app/src/main/assets/milkdrop-textures/`, come from projectM's [Milkdrop Texture Pack](https://github.com/projectM-visualizer/presets-milkdrop-texture-pack). Gradle downloads the pinned NDK and CMake if they're missing.

You need the Android SDK and JDK 17 or newer; CI builds with JDK 25. When building from the command line, set `sdk.dir=/path/to/Android/Sdk` in `local.properties`.

```bash
./gradlew :app:assembleDebug   # "Crosstune Dev", installs next to the released app; APK in app/build/outputs/apk/play/debug/
./gradlew :app:ci              # the same checks CI runs
```

There are two builds, flavors of the `store` dimension. **`play`** is the default, and the one Google Play and the GitHub releases ship: it has everything, including [casting to a TV](#casting), which uses Google Play Services. **`foss`** is the same app without a single Google class, for F-Droid and anyone who wants none; it has no casting. Plain `assembleDebug` and `assembleRelease` build both; name one with `assemblePlayDebug` or `assembleFossDebug` (APKs in `app/build/outputs/apk/<flavor>/<type>/`). Code that names a Google class lives in `app/src/play/`, behind an interface in `app/src/main/` (`Cast.kt`), with `app/src/foss/` answering it with nothing; keep it that way, as `FossBuildTest` fails the foss build's tests the day a Google class gets into it.

`:app:ci` runs the Robolectric tests with a 100% line and 85% branch coverage gate (on the `play` build, which has every line), lint on both builds, [detekt](https://detekt.dev)'s checks for tangled logic, the `foss` build's own tests (that nothing in it names a Google class), and the debug and minified release builds of `play`, and the release one of `foss`. Reports go to `app/build/reports/`. CI runs the tests, lint and builds as parallel jobs, and skips them for pull requests that only change text and tools around the app: README, this file, `AGENTS.md`, `LICENSE`, `docs/`, `fastlane/`, `screenshots/`, `site/`, `report/`, and the site, shots, recording and visuals scripts. The tests of the release, shots and visuals scripts (`test_*.py` next to them) and of the report Worker run either way.

`python3 scripts/mutants.py` runs [PIT](https://pitest.org) mutation testing on the Kotlin lines a branch changed from `origin/main`, uncommitted ones too: it changes each a little, one change at a time, runs the tests that cover it, and lists every change no test caught. Kotlin's own null checks and the code Compose adds to composables are left out, by the PIT plugin in `tools/pitest-changed-lines/`. It takes minutes to an hour, depending on the change; the full report goes to `app/build/reports/pitest/playDebug/`. It never blocks a merge: the **Mutants** workflow runs it on each pull request that changes the app's code, outside the required checks, and comments what lived.

The app ships a [baseline profile](https://developer.android.com/topic/performance/baselineprofiles/overview) in `app/src/main/generated/baselineProfiles/`, so Android compiles the code used on startup and on the main screen ahead of time, also for installs from GitHub. Builds use the committed profile and never need a device. After big changes to the screens, regenerate it on an English emulator or phone with Android 9 or newer and commit the result. It runs on every connected device, so with more than one, pick one with `ANDROID_SERIAL` (see `adb devices`):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
# Optional: compare cold starts without compilation and with the profile.
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.astrovm.crosstune.baselineprofile.StartupBenchmark
```

The version is set explicitly in `app/build.gradle.kts`: `versionName` is `X.Y.Z`, and `versionCode` is `X*1000000 + Y*10000 + Z*100`. Builds use these values even without Git history or tags.

## Releasing

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, and add the changelog under `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, in the last pull request going into the release rather than one of their own. Merge it before releasing. CI and the Release workflow require a nonempty English changelog for the current version before building. Run `python3 scripts/release/check_changelog.py` to check it locally.
2. Push a tag with `git tag vX.Y.Z && git push origin vX.Y.Z`, or run **Actions → Release → Run workflow**.
3. The [Release workflow](.github/workflows/release.yml) runs the CI checks, verifies that the APK's version matches the tag, then signs and publishes `Crosstune-vX.Y.Z.apk` (the `play` build) and `Crosstune-vX.Y.Z-foss.apk` (the `foss` build, without Google Play Services). Signing runs in the `release` environment, which only `main` and `v*` tags can use; add required reviewers to it under **Settings** → **Environments** to approve each release by hand.
4. It then sends the signed App Bundle and the English changelog to Google Play's production track, once `PLAY_SERVICE_ACCOUNT_JSON` is set. Without it, download `Crosstune-vX.Y.Z.aab` from the workflow run's **Crosstune-vX.Y.Z-play** artifact and upload it in Play Console. It's signed with the same key, which Play uses as the upload key. Set the `PLAY_TRACK` repository variable to, say, `internal` to send releases to another track. Store listing text and screenshots are still updated by hand in Play Console.

It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Output of `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Optional. JSON key of a Google Cloud service account with release access to the app in Play Console |

> Back up the keystore and never replace it. Android only installs an update if it's signed with the same key.

The release APKs are reproducible: building the same tag elsewhere gives the same APKs, apart from the signature. That's what F-Droid needs to ship one, if it's ever listed there; the `foss` APK is the one for it, as F-Droid doesn't take Google's libraries. Google Play installs update GitHub ones only if Play App Signing uses this same key. To check a release locally, build the tag from a fresh clone and run `apksigcopier compare Crosstune-vX.Y.Z-foss.apk --unsigned app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk`, or the same with `Crosstune-vX.Y.Z.apk` and `apk/play/release/app-play-release-unsigned.apk`.

## Casting

Casting a song from the phone to Crosstune on a TV is [Google Cast](https://developers.google.com/cast) with [Cast Connect](https://developers.google.com/cast/docs/android_tv): the phone is a Cast sender, and Crosstune on the Android TV or Google TV is the receiver, an Android TV app Cast launches, with no web page in between. It's in the `play` build only. A phone or TV without Google Play Services (or the `foss` build) shows no Cast button and changes nothing else.

The phone's side (`GoogleCastSender.kt`) shows a Cast button on a song only while a TV with Crosstune is on the network, hands the song to the TV in the LOAD request's `customData` and pauses it in the phone's app, then sends the TV pause, play, seek, next, previous, translation and readings. The TV's side (`GoogleCastReceiver.kt`) opens the song as one opened on the TV there (YouTube plays it, the words cover it), and tells the phone what plays through a media session. Everything that isn't Google's, the message formats, what the controls do, and the screens, is in `Cast.kt` and the view model, and is tested with fakes.

### The Cast app ID

Cast needs an app ID, which Google gives once, from the [Google Cast SDK Developer Console](https://cast.google.com/publish) (a one-time $5 registration). Until Crosstune has its own, `castAppId` in `gradle.properties` is the placeholder `00000000`, which no receiver answers to: everything builds and passes its tests, and the Cast button never shows. To register it:

1. In the console, **Add new application** → **Custom Receiver**, and name it Crosstune. The console asks for a **Receiver Application URL**: Cast Connect launches the Android TV app instead and never loads it, so `https://crosstune.4st.li` will do (only a Chromecast without Android TV would open it, and Crosstune doesn't work there). Copy the **Application ID** it shows.
2. In the app's settings, add the **Android TV app package name** Crosstune has on the TV: `com.astrovm.crosstune` (and `com.astrovm.crosstune.dev` for the dev build, if it's to be cast to).
3. **Add new device** with the Cast serial number of each TV you test on (the console's device page says where to find it), as long as the app is unpublished. Without it, Cast Connect only works for a TV app installed from Google Play; with it, the dev build works. It takes up to 15 minutes to apply, and the TV has to be restarted.
4. Put the ID in one place, `castAppId` in `gradle.properties`, or pass `-PcastAppId=ABCD1234` to Gradle for one build. It becomes `BuildConfig.CAST_APP_ID`, which the phone's and the TV's sides both read.
5. **Publish** the app in the console when a release goes to people who aren't registered test devices.

Only a TV running Android TV or Google TV can receive a cast: a Chromecast without it has no Crosstune to launch.

Store listing text lives in [`fastlane/metadata/android`](fastlane/metadata/android).

The website at [crosstune.4st.li](https://crosstune.4st.li) is built from [`site/`](site): templates plus one strings file per language. After changing it, run `python3 scripts/build-site.py` and commit `docs/` too.

## Reports

**Report a problem** and **Report song problems automatically** send to a Cloudflare Worker in [`report/`](report), at `report.crosstune.4st.li`. It keeps reports in the D1 database `crosstune-reports` (table in `schema.sql`), at most 5 a minute from one address, and a daily cron deletes those older than 180 days. Test it with `node --test report/*.test.mjs`, and deploy with `cf deploy` from that folder.

## Other tools

- [`scripts/lyrics/`](scripts/lyrics): an experiment that checks lyrics timing against Japanese vocals heard by faster-whisper, on the computer. See its README.
- [`scripts/tv-banner.py`](scripts/tv-banner.py) redraws the TV launcher banner, `tv_banner.xml`, from the launcher logo and a Roboto font file.

## Screenshots and clips

The store's screenshots and the site's clips, in every language, come from [`scripts/shots/shots.py`](scripts/shots/shots.py), with no phone or emulator:

```bash
python3 scripts/shots/shots.py          # every language, both themes
python3 scripts/shots/shots.py ja ko    # only these
python3 scripts/shots/shots.py --theme light  # only the light set
```

It runs the `:app:shots` Gradle task, which draws the app's real screens with Robolectric on a clock that moves one frame at a time, so every run gives the same pictures, and copies both themes’ clips into `docs/img/`. Dark store screenshots go into `fastlane/metadata/android/`; the matching light alternatives are kept in `screenshots/light/<store locale>/`. The site selects clips and stills for its current theme, including system theme changes and reduced motion. The MilkDrop visuals behind the lyrics are drawn ahead with `render.py`, below, from a made-up beat. The scenes live in [`Shots.kt`](app/src/test/java/com/astrovm/crosstune/shots/Shots.kt).

## Recording the app

Screen recordings for promos come out steadier with [`scripts/record`](scripts/record) than with `adb screenrecord`, which drops frames. Start the emulator with `-gpu host -cores 6 -memory 4096`, then:

```bash
scripts/record/emulator.sh setup es-AR 01:46   # dark, clean status bar, clock and language
scripts/record/record.sh playlist.mp4 sh -c 'adb shell input tap 400 1005; sleep 7'
scripts/record/emulator.sh restore
```

`record.sh` records with [scrcpy](https://github.com/Genymobile/scrcpy) while Android's animations run at half speed, and plays the take back at double speed, so each of its 60 frames a second is a real one. Drive it at half speed too: double the waits and swipe durations.

[`scripts/milkdrop-render/render.py`](scripts/milkdrop-render/render.py) draws the MilkDrop visuals for any song into a 60 fps video, with the app's projectM, presets and settings:

```bash
python3 scripts/milkdrop-render/render.py song.mp3 visuals.mp4 --start 60 --seconds 8 --preset 17
```

Run it without `--preset` to list the presets. It builds projectM from `third_party/projectm` into `build/milkdrop-render/` the first time, with a clock that moves one frame per frame, so slow renders still keep time with the song. It needs ffmpeg, CMake, Ninja, and the EGL and OpenGL headers.

Added or removed a preset? [`textures.py`](scripts/milkdrop-render/textures.py) copies the images the presets draw with from a checkout of the texture pack, and names those it lacks. Then [`thumbs.py`](scripts/milkdrop-render/thumbs.py) redraws the stills **Settings** → **Visuals** shows for them, into `app/src/main/assets/milkdrop-thumbs/`. Also list each new one under its kind in [`milkdrop-kinds.txt`](app/src/main/assets/milkdrop-kinds.txt): the folder it's in in projectM's Cream of the Crop pack, or `classics`.

```bash
git clone https://github.com/projectM-visualizer/presets-milkdrop-texture-pack ../texture-pack
python3 scripts/milkdrop-render/textures.py ../texture-pack/textures
python3 scripts/milkdrop-render/thumbs.py
```

To see how fast each visual draws on a phone, turn on the debug mode, then open the visuals. Each one draws as fast as it can for 1.5 s, and the log says its frames a second, by preset name, then all of them, slowest first:

```bash
adb shell setprop debug.crosstune.visuals_bench 1
adb logcat -s CrosstuneVisuals
```

A visual slower than 40 frames a second on a phone is skipped there from then on, as it makes the words over it stutter.

When a still image comes right before a video in an edit, use a frame of that video instead: browsers, and tools built on them, draw video a little lighter than images, so the screen jumps in brightness.
