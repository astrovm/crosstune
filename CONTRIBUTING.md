# Contributing

See [`AGENTS.md`](AGENTS.md) for how the code is laid out and checked.

## Building

Clone with `git clone --recurse-submodules`, or run `git submodule update --init --recursive` in a clone: the MilkDrop visuals build [projectM](https://github.com/projectM-visualizer/projectm) (LGPL 2.1) from `third_party/projectm`. Its presets in `app/src/main/assets/milkdrop/` come from projectM's [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop), [Milkdrop Original](https://github.com/projectM-visualizer/presets-milkdrop-original) and [En D](https://github.com/projectM-visualizer/presets-en-d) packs, and from Ryan Geiss's [favorite presets of January 2021](https://www.geisswerks.com/about_milkdrop.html), by their authors in each file name. 46 are Geiss's copies, with his own ratings, and 6 of those are in no projectM pack. The images some of them draw with, in `app/src/main/assets/milkdrop-textures/`, come from projectM's [Milkdrop Texture Pack](https://github.com/projectM-visualizer/presets-milkdrop-texture-pack). Gradle downloads the pinned NDK and CMake if they're missing.

The submodules stay untouched. Where projectM or whisper.cpp print a warning when built, [`app/src/main/cpp/patch.cmake`](app/src/main/cpp/patch.cmake) fixes a copy in the build folder, and stops the build if an update moved the text it replaces. Drop each fix once the submodule has it. The same edits are in [projectM PR 1076](https://github.com/projectM-visualizer/projectm/pull/1076) and [whisper.cpp PR 4167](https://github.com/ggml-org/whisper.cpp/pull/4167).

Every work by others the APK holds or downloads has a notice in [`notices.txt`](app/src/main/resources/com/astrovm/crosstune/notices/notices.txt), with its license's full text beside it. The app shows them in **Settings** → **About** → **Licenses**, and README's table matches them. Each text is copied byte for byte from where its work publishes it, at the version the app uses: [`notice-texts.txt`](app/src/test/resources/com/astrovm/crosstune/notice-texts.txt) records its source and SHA-256, `python3 scripts/notices.py check` fetches each source again and compares it, and `fetch` copies them. `./gradlew :app:notices` (in `:app:ci`) builds the release native code and fails on anything in the release APK with no notice: each library and each licence file inside one, each file dependency, each submodule, each compiled source and header, the NDK's libraries, each bundled file and each download. A licence or origin line in a compiled file, like "Copyright" or "adapted from", needs a `covers: source PATH ~ WORDS` line naming that file and words in that line. It fails too on a `covers:` line that covers nothing.

CI logs have no warnings: one that shows up is a bug to fix, never to hide. Gradle is 9.9.0. Kover 0.9.11 still calls `Configuration.setVisible`, which Gradle 9.8 deprecated and Gradle 11 will remove. The fix is on Kover's main branch ([PR 838](https://github.com/Kotlin/kotlinx-kover/pull/838), [issue 836](https://github.com/Kotlin/kotlinx-kover/issues/836)) and not in a release. Its report tasks also call `Task.getAnt()`. That call is still on Kover's main branch, and it is the use Gradle 10 removes. Renovate does not hold Gradle back. The Kotlin and Java compilers stop on a warning, so a new one fails the build. `python3 scripts/ci-warnings/ci_warnings.py --branch main` counts what is left in the logs, per job and per kind; `--lines` lists them.

You need the Android SDK and JDK 17 or newer; CI builds with JDK 25. When building from the command line, set `sdk.dir=/path/to/Android/Sdk` in `local.properties`.

```bash
./gradlew :app:assembleDebug   # "Crosstune Dev", installs next to the released app; APK in app/build/outputs/apk/debug/
./gradlew :app:ci              # the same checks CI runs
```

`scripts/gradle-box/gradle-box.sh` runs any of these in a container instead, with only the checkout, the SDK and the debug key in it. Git is in that image, built from `eclipse-temurin:25-jdk` plus Git, so the same box works on Linux and on a Mac. On a Mac it runs in podman's machine, which has to be on (`podman machine start`), and gets no more memory or CPUs than that machine has; a build is given 16 GiB where there is that much (`podman machine set --memory` gives the machine more). Several checkouts on one machine build side by side while the memory they ask for fits in three quarters of the RAM, then take turns, and it says how many builds are ahead of yours. A run of a few test classes needs far less than a full build: `GRADLE_BOX_MEMORY=7` lets three of those run at once where one full build would. It also waits while the machine is out of memory, and says when the machine ran out of memory during a build, since a test that times out then may not be broken. Downloads are kept and shared, so a new worktree or an empty slot doesn't fetch them again. It needs podman and python3, and nothing else a Mac doesn't come with.

`:app:ci` runs the Robolectric tests with a 100% line and 88% branch coverage gate, lint, [detekt](https://detekt.dev)'s checks for tangled logic, and the debug and minified release builds. Reports go to `app/build/reports/`. CI runs the tests, lint and builds as parallel jobs, and skips them for pull requests that only change text and tools around the app: README, this file, `AGENTS.md`, `LICENSE`, `docs/`, `fastlane/`, `screenshots/`, `site/`, `report/`, `extension/`, and the site, shots, recording, visuals, build box, CI warnings and smoothness scripts. The tests of the release, shots, visuals, build box and smoothness scripts (`test_*.py` next to them) and of the report Worker run either way.

`MatcherCasesTest` holds the YouTube Music matcher to what `main` opens: the inputs its reviews tried, a matrix of about 83,000 searches and 4,000 made-up ones, each in 7 ways (signed out, as ChatGPT picks, and with the link's own length alone), against `main`'s answers in `app/src/test/resources/matcher-*-main.tsv.gz`. It runs one in 20 of the matrix and the made-up searches; `-PmatcherFull` runs all, as CI's **matcher** job does when a pull request changes `ExactMatcher.kt`, `SongNames.kt`, the test or those files, and the **Mutants weekly** workflow does each week. A change that changes what opens makes them again on its own code: set `onMain` in the test, run it with `-PmatcherSongs=KEY,...` in parts short enough, and put the parts' lines together in the matrix's order; or gzip the `matcher-*-now.tsv` files from the **matcher** job's artifact. Its PR shows the counts the test prints, before and after.

`python3 scripts/mutants.py` runs [PIT](https://pitest.org) mutation testing on the Kotlin lines a branch changed from `origin/main`, uncommitted ones too: it changes each a little, one change at a time, runs the tests that cover it, and lists every change no test caught. Kotlin's own null checks and the code Compose adds to composables are left out, by the PIT plugin in `tools/pitest-changed-lines/`. It takes minutes to an hour, depending on the change; the full report goes to `app/build/reports/pitest/debug/`. It never blocks a merge: the **Mutants** workflow runs it on each pull request that changes the app's code, outside the required checks, and comments what lived.

The app ships a [baseline profile](https://developer.android.com/topic/performance/baselineprofiles/overview) in `app/src/main/generated/baselineProfiles/`, so Android compiles the code used on startup and on the main screen ahead of time, also for installs from GitHub. Builds use the committed profile and never need a device. After big changes to the screens, regenerate it on an English emulator or phone with Android 9 or newer and commit the result. It runs on every connected device, so with more than one, pick one with `ANDROID_SERIAL` (see `adb devices`):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
# Optional: compare cold starts without compilation and with the profile.
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.astrovm.crosstune.baselineprofile.StartupBenchmark
```

`scripts/perf/perf.py` measures how smooth the dev app is on a phone, the same way each time: janky frames while the main screen scrolls (`jank`), main-thread and app CPU on what is on screen (`cpu`), and cold starts (`start`). Each run prints a line, and each build a mean and spread. With `--build LABEL=APK` given twice, it installs the two builds in turns, so both get the same phone as it warms up. A debug build runs most of its code in Android's interpreter, so measure a release-like one: `-PperfBuild :app:assemblePerf` builds the minified release as "Crosstune Dev", signed with the debug key.

```bash
ANDROID_SERIAL=SERIAL python3 scripts/perf/perf.py jank --runs 10 --build main=main.apk --build branch=branch.apk
```

`-PcomposeReports` writes the Compose compiler's reports to `app/build/compose/`: the classes it can't call stable and the composables it can't skip. With the Kotlin built into the Android Gradle plugin, a change to the compiler's options doesn't make Kotlin compile again, so add `--rerun` to `:app:compileDebugKotlin`. `app/compose-stability.conf` lists the classes the app never changes once made, so Compose skips a screen handed an equal copy.

The version is set explicitly in `app/build.gradle.kts`: `versionName` is `X.Y.Z`, and `versionCode` is `X*1000000 + Y*10000 + Z*100`. Builds use these values even without Git history or tags.

## Releasing

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, and add the changelog under `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, in the last pull request going into the release rather than one of their own. Merge it before releasing. CI and the Release workflow require a nonempty English changelog for the current version before building. Run `python3 scripts/release/check_changelog.py` to check it locally.
2. Push a tag with `git tag vX.Y.Z && git push origin vX.Y.Z`, or run **Actions → Release → Run workflow**.
3. The [Release workflow](.github/workflows/release.yml) runs the CI checks, verifies that the APK's version matches the tag, then signs and publishes `Crosstune-vX.Y.Z.apk`. Signing runs in the `release` environment, which only `main` and `v*` tags can use; add required reviewers to it under **Settings** → **Environments** to approve each release by hand.
4. It then sends the signed App Bundle and the English changelog to Google Play's production track, once `PLAY_SERVICE_ACCOUNT_JSON` is set. Without it, download `Crosstune-vX.Y.Z.aab` from the workflow run's **Crosstune-vX.Y.Z-play** artifact and upload it in Play Console. It's signed with the same key, which Play uses as the upload key. Set the `PLAY_TRACK` repository variable to, say, `internal` to send releases to another track. The store listing has its own workflow, [`store-listing.yml`](.github/workflows/store-listing.yml): a change on `main` to a name, a description or a picture in `fastlane/metadata/android/` sends the English listing to Google Play with the same key. Run it by hand to send another branch, to add a language the store doesn't have yet (name its folders in **languages**), or with **check_only** to ask Play whether it would take the listing. Google reviews a listing before it shows.
5. On Play, each release lands in internal testing first, which Google doesn't review, so testers there get it in minutes. The same release then goes on to `PLAY_TRACK` with its notes, through Google's review.

It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Output of `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Optional. JSON key of a Google Cloud service account with release access to the app in Play Console |

> Back up the keystore and never replace it. Android only installs an update if it's signed with the same key.

The release APK is reproducible: building the same tag elsewhere gives the same APK, apart from the signature. That's what F-Droid needs to ship it, if it's ever listed there. Google Play installs update GitHub ones only if Play App Signing uses this same key. To check a release locally, build the tag from a fresh clone and run `apksigcopier compare Crosstune-vX.Y.Z.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk`.

Store listing text lives in [`fastlane/metadata/android`](fastlane/metadata/android).

The website at [crosstune.cc](https://crosstune.cc) is built from [`site/`](site): templates plus one strings file per language. After changing it, run `python3 scripts/build-site.py` and commit `docs/` too. The **Pages** workflow publishes `docs/` when it changes on main, then has Cloudflare check its cached copies against the new files. Pages publishes from Actions: **Settings** → **Pages** → **Source** → **GitHub Actions**.

## Chord sheet widths

Chords from J-Total sit over the letter they sit over on J-Total's page. The page draws some letters at widths that aren't whole columns (U+0132 is 1.470 columns, U+0133 is 0.888). `ChordSheets` uses Chromium's measurements in [`jtotal-widths.txt`](app/src/main/resources/com/astrovm/crosstune/jtotal-widths.txt) and [`jtotal-widths-mono.txt`](app/src/main/resources/com/astrovm/crosstune/jtotal-widths-mono.txt), one for each font stack the pages use. Each header records the browser, font packages, aliases and Inconsolata file hashes. Remake both with `python3 scripts/jtotal-widths/jtotal_widths.py generate`, which measures in a container (it needs podman and downloads the three Inconsolata files once); `check` compares both tables with the browser. Other machines can draw some scripts at different widths because they have different fonts.

[`compare_sheets.py`](scripts/jtotal-widths/compare_sheets.py) measures how well that works on crawled sheets: Chromium draws each page, and the script says which chords the app puts over another letter than the page does. `crawl` saves the pages (one request a second, into the agent cache, never into Git), `browser` draws them in the container, `app` runs this checkout's `ChordSheets` on them in the test box, and `compare` prints the counts. Its top has the commands. `app --old` does the same for a checkout from before the app read a page's font stack and Windows-31J.

## Japanese readings

[`jmdict-readings.txt`](app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt) lists the words of two kanji or more that [JMdict](https://www.edrdg.org/dokuwiki/doku.php?id=wiki_index.php_jmdict-edict_dictionary_project) reads one way only, where IPADIC or UniDic read them another way: 体中 is からだじゅう, not からだちゅう. `Readings` reads a run of whole words as the file says. JMdict is by the Electronic Dictionary Research and Development Group, under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); the file's header says so, with JMdict's and the licenses' links, how the words were changed, the JMdict date and the [jmdict-simplified](https://github.com/scriptin/jmdict-simplified) version. A new file made from JMdict gets a `covers:` line in JMdict's notice.

Remake it with `python3 scripts/readingsaudit/readingsaudit.py table`, with `jmdict.json` (jmdict-eng) in the audit folder; `fetch` puts the newest one there. It runs `JmdictReadings` in the build box, which keeps a word only where the app reads it otherwise. Then measure the change with the same script's `run` and `compare --before`, on a copy of the audit from before; its top has the commands.

The Group's licence asks for its data to be updated regularly from the newest version. The [JMdict workflow](.github/workflows/jmdict.yml) does that on the 3rd of each month: `fetch`, `table` and `oldkana`, with Gradle run as CI runs it (`table --gradle`). `fetch` takes jmdict-eng by its release's tag and keeps it only if it has the size and SHA-256 the release gives. When either file changed, the workflow opens one pull request from `jmdict/remake`, or updates the open one. GitHub holds the runs of a pull request that a workflow opened or pushed: CI runs on it after a person with write access selects **Approve workflows to run** in its merge box, and the pull request's body says so. The workflow stops when a person has pushed to that branch. [`propose.sh`](scripts/readingsaudit/propose.sh) does that part, and `propose.test.sh` tries it on a local repository with a fake `gh`. Run it by hand with **Actions** → **JMdict** → **Run workflow**, optionally with a release tag. A tag whose JMdict is older than the committed files' stops `table` and `oldkana` with that reason: the licence asks for the newest version, so a remake never goes back. The workflow opens its pull request only when **Settings** → **Actions** → **General** → **Workflow permissions** → **Allow GitHub Actions to create and approve pull requests** is on. When it's off, the run pushes `jmdict/remake`, then fails and names that setting. The next run opens the pull request. It pushes the branch again first when main has moved or the lists have changed since, as the branch is always made again from the newest main. The Release workflow fails when a file's JMdict is more than 90 days old (`readingsaudit.py age`): three monthly runs missed or left unmerged. A readings test that fails on that pull request means JMdict now reads a word another way: find out which reading is right, and fix the reading or the list, not the test.

[`old-kana.txt`](app/src/main/resources/com/astrovm/crosstune/old-kana.txt) helps `Readings` read old kana in kana alone, which IPADIC splits: おもひで is おもいで. It lists JMdict's common words in kana with い or う after their first kana, each with the form it's looked up by, and the words in kana written with ひ or ふ today, おひる or くふう, where a ひ or ふ is no old kana. It's from JMdict as well, under the same license, and its header says so. Remake it with `python3 scripts/readingsaudit/readingsaudit.py oldkana`, with `jmdict.json` in the audit folder. Measure a change to old kana with `OldKanaNotes`, which lists every line that gets an old spelling note, with and without UniDic: `./gradlew :app:songCheck -PsongcheckTest=com.astrovm.crosstune.songcheck.OldKanaNotes -PsongcheckDir=FOLDER`, with the audit folder as `FOLDER` so its songs in `found/` count too. Then `diff` its `oldkana/` against a run from before. A note on a modern line is a false note.

## Reports

**Report a problem** and **Report song problems automatically** send to a Cloudflare Worker in [`report/`](report), at `report.crosstune.cc`. It keeps reports in the D1 database `crosstune-reports` (table in `schema.sql`), and a daily cron deletes those older than 180 days. D1's Time Travel keeps deleted rows up to 30 days more and can't be turned off, so the privacy text says so. It takes them only as JSON, at most 5 a minute from one address (an IPv6 address by its /64). In a day (UTC), each kind (what a person sends, what the app finds live, timings kept by an earlier version) has its own limits: 10, 70 and 200 from one network (an IPv4 /24, an IPv6 /48, a 6to4 address by the IPv4 /24 inside it), room for 3 phones on their busiest day; then 500, 2500 and 3000 in the day, whoever sent them; and 50, 150 and 150 MB in the table, counted in bytes. So a flood of one kind leaves the others their room, the day's rows written stay inside D1's free plan, and the database can't be filled. Its comments, and PR #955, say why those numbers. There is no limit for a wider block or a carrier: one low enough to cost a flood something would turn away the honest phones of one carrier as use grows. A report's day comes from one clock, the Worker's: when it came in (`at`, written by the Worker), its day's share, its network's day and its refusal. Networks are counted under an HMAC-SHA256 of the day, the kind and the network, with the secret `NETWORK_KEY`: without the secret the key can't be traced back to the network, and the cron deletes past days. Without the secret it keeps nothing and answers 503. The counts and the refusals (turned away, each day, why and of what kind) are tables in [`limits.sql`](report/limits.sql), with triggers that keep the counts exact, and the Worker reads every limit in one statement before it writes anything. Then one batch, a single transaction, puts the report in only while its limits have room, counts its network only if it went in, and reads why if it didn't. So reports sent at once can't take a place past a limit, and a network is never counted without its report. Run it on the live database before deploying the Worker that uses it: its top says how. When you edit reports by hand, never use `REPLACE`, which deletes a row without its trigger, and run `limits.sql` again after any edit the triggers don't see. A refused report says why in the `X-Crosstune-Refused` header, which the app doesn't read: it reads the status. So "not now" (a limit, the minute, a missing secret) is always 429 or 503, and a report that is never taken is 400, 413 or 415; a test holds both. A report about timing also carries the player's package, speed, the lead and the last minute of `TimingLog` (columns `app_package`, `speed`, `lead_ms`, `early_ms`, `timing_log`); `schema.sql` lists the `ALTER TABLE`s to run on the live database before deploying a Worker that stores them. Test it with `node --test report/*.test.mjs`, and deploy with `npx wrangler deploy` from that folder (`cf deploy` finds no dev server there and stops).

Versions of the app from before crosstune.cc send to `report.crosstune.4st.li`. That address is a second Worker, [`forward.js`](report/forward.js), in the account that holds 4st.li: it counts a report against its sender's address and passes it on, with a key (the secret `FORWARD_KEY`, set on both Workers) that tells the main Worker it was counted, and the sender's address in `X-Crosstune-Sender`, which the main Worker counts the day's limit by and doesn't keep. The two Workers are in two accounts, so each counts the minute's limit on its own. Deploy it with `npx wrangler deploy -c forward.wrangler.jsonc`. When what it passes on changes, deploy it before the main Worker: the main Worker ignores headers it doesn't read yet, and the new one has them from its first report.

[`scripts/reports/reports.py`](scripts/reports/reports.py) goes through them: it reads the table, only ever reads it, into `build/`, shows the reports turned away each day and why, groups the reports by problem, version and song, and runs their songs through the app's real finders with `SongCheck`. Its top says how. They're people's songs, so they stay out of the repo, which is public. A pull request that only changes this script skips the app's CI jobs too, and its tests run either way.

## Browser extension

[`extension/`](extension) opens a music link in the service picked in its options, and shows the lyrics of what's playing on a music site in a side panel (Firefox's sidebar), in Chrome, Edge, Brave and Firefox. TypeScript, esbuild and no framework; it needs Node 24 or newer.

```bash
cd extension
npm ci
npm run build       # dist/chrome and dist/firefox
npm run typecheck
npm test            # unit tests on recorded answers in test/fixtures/, no network
node scripts/try.mjs 'https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT' youtubemusic   # the real logic on live services
node scripts/try-lyrics.mjs 'Africa' 'Toto' 272000   # the lyrics finder on live sites, what it found and where
npm run e2e         # headless Chromium with the built extension: a click on a Spotify link ends on YouTube Music
npm run e2e:firefox # the same in headless Firefox, through geckodriver
npm run e2e:panel   # every state of the lyrics panel in Chromium, no network: a page playing silence and recorded answers; screenshots in dist/panel-shots/
npm run package     # the stores' zips in dist/: Chrome and Edge, Firefox, and the source Firefox's reviewers build from
npm run screenshots # the stores' 1280×800 screenshots in each language, in dist/screenshots/
npm run icons       # the icons again from the app's store icon, when it changes
```

Load it unpacked: in Chrome, Edge or Brave open `chrome://extensions` (`edge://extensions`, `brave://extensions`), turn on **Developer mode**, **Load unpacked**, and pick `extension/dist/chrome`. In Firefox open `about:debugging#/runtime/this-firefox`, **Load Temporary Add-on**, and pick `extension/dist/firefox/manifest.json`. Firefox keeps the sites an extension may read off until you allow them, so open the extension's options and press **Allow**; they're the music sites' pages and the public endpoints the app already asks. It follows the app's logic (`LinkResolver`, `ExactMatcher`, `Destinations`) but not the parts that need the app, such as the ChatGPT pick or Shazam. The toolbar button opens the lyrics, and the panel's gear the options. `src/content.ts` reads the song from the page's Media Session and the position from its `<video>` or `<audio>`; `src/lyrics.ts` follows the app's `LyricsFinder`, without the covers, romaji and sentence case; `src/panel.ts` draws it and asks only while it's open. CI builds and tests it when `extension/` changes, and runs `npm run e2e:panel`. `npm run e2e` needs the network and a Chromium download (`npx playwright install chromium`), so it runs by hand. So does `npm run e2e:firefox`, with Firefox and geckodriver on the PATH; a snap's geckodriver can't be stopped when it's done, so point `GECKODRIVER` and `FIREFOX` at [geckodriver's release](https://github.com/mozilla/geckodriver/releases) and [Firefox's own build](https://www.mozilla.org/firefox/linux/) instead. Both browser tests show the browser as people's does, since Spotify tells headless browsers what it hides from people's.

Its strings are in `_locales/`, in the app's 16 languages; `test/locales.test.ts` fails when one is missing a message. A new version goes out when `version` in `package.json` changes on main: the **Extension release** workflow sends it to each store whose keys are set and tags it `extension-<version>`. Each store's first version is uploaded by hand; [`extension/store/README.md`](extension/store/README.md) has the listing text, the answers the dashboards ask for, and where each key comes from.

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

Screen recordings for promos come out steadier with [`scripts/record`](scripts/record) than with `adb screenrecord`, which drops frames. They run on `crosstune-phone` ([`scripts/emulator`](scripts/emulator/README.md)) and need no root:

```bash
python3 scripts/emulator/emulator.py start crosstune-phone
scripts/record/emulator.sh setup es-AR 01:46   # dark, clean status bar, clock and language
scripts/record/record.sh playlist.mp4 sh -c 'adb shell input tap 400 1005; sleep 7'
scripts/record/emulator.sh restore
```

`record.sh` records with [scrcpy](https://github.com/Genymobile/scrcpy) while Android's animations run at half speed, and plays the take back at double speed, so each of its 60 frames a second is a real one. Drive it at half speed too: double the waits and swipe durations.

Several agents share the test emulators and the Samsung phone. Hold the device with [`scripts/devicelock.py`](scripts/devicelock.py) for about as long as the job, instead of locking the file by hand. On mr the locks are `/mnt/data/agent-cache/DEVICE.lock`, one for `samsung` and one for each emulator in `scripts/emulator/emulators.json`. Elsewhere they are in `~/.cache/agent-cache`. If that folder is missing, the command says so.

```bash
python3 scripts/devicelock.py take crosstune-phone --who "promo" --minutes 15
python3 scripts/devicelock.py who
python3 scripts/devicelock.py release crosstune-phone --who "promo"
```

`take` waits its turn. An earlier waiter gets the device before a later one, and taking it again while others wait puts you at the back of the line. Taking it again while you already hold it, and nobody is waiting, returns at once. A detached holder lets go when the minutes are up. `release` only ends your own hold: the one this shell session took, or, with `--who`, the one taken under that name, since an agent's shell calls each run in their own session. `release --force` ends someone else's and prints who that was. `who` reads the holder from the lock itself.

[`scripts/milkdrop-render/render.py`](scripts/milkdrop-render/render.py) draws the MilkDrop visuals for any song into a 60 fps video, with the app's projectM, presets and settings:

```bash
python3 scripts/milkdrop-render/render.py song.mp3 visuals.mp4 --start 60 --seconds 8 --preset 17
```

Run it without `--preset` to list the presets. It builds projectM from `third_party/projectm` into `build/milkdrop-render/` the first time, with a clock that moves one frame per frame, so slow renders still keep time with the song. It needs ffmpeg, CMake, Ninja, and the EGL and OpenGL headers.

[`scripts/contrast/contrast.py`](scripts/contrast/contrast.py) measures how readable the lyrics are over the visuals, from real screenshots on a phone, for each kind of text. It needs NumPy, SciPy and Pillow; the top of the script says what the phone needs:

```bash
python3 scripts/contrast/contrast.py shots build/contrast/after 0 10 51 98 14 7
python3 scripts/contrast/contrast.py report build/contrast/after
```

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
