# Crosstune

Crosstune opens music links from one service in the app you actually use. Share, paste or tap a link from Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud or Bandcamp, and Crosstune opens the same song, album, artist or playlist in any of them, or in Amazon Music.

<p align="center">
  <img src="screenshots/Screenshot_20260325_002742_Crosstune.jpg" alt="Home screen" width="320">
  &nbsp;&nbsp;
  <img src="screenshots/Screenshot_20260325_002852_Crosstune.jpg" alt="Resolved track" width="320">
</p>

## What You Can Do

- Choose which services' links Crosstune opens when you tap them, from Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud and Bandcamp. Links from any service can also be shared to Crosstune or pasted.
- Send each service's links to a different place, e.g. YouTube videos to YouTube Music and everything else to Apple Music.
- Add custom destinations: any app or website with a search URL, such as `https://example.com/search?q={query}`.
- Open songs, albums, artists and playlists.
- Choose a default destination, or turn on **Ask where to open shared links** to pick one for each link.
- Turn on **Open exact matches when possible** to open Apple Music and Deezer items directly instead of a search.
- Open a result, or a link Crosstune couldn't read, in the app it came from.
- Use the **Open copied music link** Quick Settings tile or launcher shortcut to open whatever link you copied.
- Re-open recent links from the history list.
- Copy the search text or share a search link.

## Supported Links

| Service | Links | Reads details from |
| --- | --- | --- |
| Spotify | `open.spotify.com/{track,album,artist,playlist}/…` (including `/intl-xx/`), `spotify.link/…`, `spotify:…` URIs, bare track IDs | Public page |
| YouTube Music | `music.youtube.com/watch?v=…` | YouTube oEmbed |
| YouTube | `youtube.com/watch?v=…`, `youtu.be/…`, `/shorts/…`, `/live/…` | YouTube oEmbed |
| Apple Music | `music.apple.com/{region}/{song,album,artist,playlist}/…` | iTunes Lookup API (playlists: public page) |
| Deezer | `deezer.com/{track,album,artist,playlist}/…`, `link.deezer.com/…` | Deezer API |
| TIDAL | `tidal.com/{track,album,artist,playlist}/…`, `listen.tidal.com/…` | Public page |
| SoundCloud | `soundcloud.com/{artist}`, `/{artist}/{track}`, `/{artist}/sets/{set}`, `on.soundcloud.com/…` | SoundCloud oEmbed |
| Bandcamp | `{artist}.bandcamp.com/{track,album}/…` | Public page |

Amazon Music is a destination only: its links show no details without JavaScript. YouTube playlists and channels aren't supported for the same reason.

The first time you open Crosstune, a short setup asks which services' links it should open, where to send them (installed apps are listed first), and walks you through allowing those links in Android. Android doesn't let third-party apps verify these domains, so you allow them under "Open by default"; on Android 12 and later, setup shows which ones are allowed so far. Everything can be changed later under **Settings**. Pages Crosstune can't convert, like a SoundCloud feed, go straight to the service's own app. If you tap a link before finishing setup, Crosstune asks where to open it and shows setup on the next launch.

Updating from a version before setup existed keeps Spotify links opening in Crosstune and skips setup.

## Privacy

Crosstune has no accounts, analytics or ads. It reads a link's title and artist from the service it belongs to, using the sources in the table above. With exact matches turned on, it also sends that title and artist to [Apple's iTunes Search API](https://performance-partners.apple.com/search-api) or [Deezer's API](https://developers.deezer.com/api) when you open in those apps. Other destinations have no public search API that works without a key, so they open a search. History stays on your device.

## Install

Download `Crosstune-vX.Y.Z.apk` from [Releases](https://github.com/astrovm/crosstune/releases), or add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically.

Release APKs are signed with this certificate (SHA-256):

```text
64:EC:D5:52:E3:4B:B4:15:8A:04:6B:DE:1B:BD:BC:C9:04:23:9D:59:33:CF:74:C4:62:B6:FD:02:A7:B0:F6:FA
```

Each release's notes repeat the fingerprint. Releases up to v1.0.6 used a different key, so uninstall v1.0.6 once before installing a newer release.

Store metadata for F-Droid and similar catalogs lives in [`fastlane/metadata/android`](fastlane/metadata/android).

## Build from Source

### Requirements

- Android SDK
- JDK 17+
- Android Studio or Gradle CLI

If building from CLI, ensure `local.properties` has your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Build

```bash
./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Test

```bash
./gradlew :app:ci
```

Runs exactly what CI runs: the Robolectric unit tests with a 100% line coverage gate, lint, and the debug and minified release builds. Coverage and lint reports end up in `app/build/reports/`.

### Install on Device (ADB)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releasing

The [Release workflow](.github/workflows/release.yml) runs the same checks as CI (tests, coverage, lint and a minified release build), signs it, and publishes it to a GitHub release as `Crosstune-vX.Y.Z.apk`. You can start it either way:

- Push a tag: `git tag vX.Y.Z && git push origin vX.Y.Z`
- Or run **Actions → Release → Run workflow**, pick the branch, and enter the tag. The workflow creates the tag on that commit.

It requires these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Keep the keystore backed up and never replace it: Android only installs updates signed with the same key. Releases after v1.0.6 use a new signing key, so v1.0.6 must be uninstalled once before installing a newer release.

## Notes

- The app uses public Spotify page metadata (no Spotify API key needed).
- If Spotify changes their page metadata format, some links may stop resolving until updated.
- Android version metadata is derived from Git at build time:
  `versionName` uses the latest tag (without a leading `v`) and adds `-dev.N` when commits exist after that tag, while `versionCode` uses total commit count.
