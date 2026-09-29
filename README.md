# Crosstune

**Open any music link in your favorite app.**

A friend sends you a Spotify link, but you use YouTube Music? Tap it and the same song opens in YouTube Music. Works with songs, albums, artists and playlists.

<p align="center">
  <img src="screenshots/home.png" alt="A Spotify link resolved to its song, ready to open in YouTube Music" width="200">
  <img src="screenshots/recent.png" alt="The result with recent links and their covers" width="200">
  <img src="screenshots/settings.png" alt="Settings: default app and which services' links Crosstune opens" width="200">
  <img src="screenshots/setup.png" alt="First-run setup: choosing which links Crosstune opens" width="200">
</p>

## ⬇️ Get it

- **APK:** [Releases](https://github.com/astrovm/crosstune/releases)
- **Auto-updates:** add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium)
- **Needs:** Android 8.0+

> ⚠️ **Coming from v1.0.6 or older?** Uninstall once first. The signing key changed.

## 🚀 Set up (1 minute)

1. Open Crosstune. Pick the services people send you links from, and the app you listen in.
2. Tap **Open link settings**, then **Add link**, and turn on the links Crosstune lists.
3. Done. Now tap a music link anywhere and it opens in your app.

You can also paste a link into Crosstune, or share a link to it from any app.

## What it does

- **Works with 9 services**: reads links from Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud and Bandcamp, and opens them in any of those or in Amazon Music.
- **A different app per service**: for example, YouTube links go to YouTube Music and everything else goes to Apple Music.
- **Ask which app to use**: choose an app every time you share a link.
- **Open the exact song**: Apple Music and Deezer can open the song itself instead of a search.
- **Your own apps**: add any app or website that has a search page, like `https://example.com/search?q={query}`.
- **Shortcuts**: paste with one tap, reopen recent links, or use the Quick Settings tile and the home screen shortcut.

## Supported links

| Service | Works with |
| --- | --- |
| Spotify | Songs, albums, artists, playlists, `spotify.link`, `spotify:` URIs |
| YouTube Music | Songs |
| YouTube | Videos, Shorts, live, `youtu.be` |
| Apple Music | Songs, albums, artists, playlists |
| Deezer | Songs, albums, artists, playlists, `link.deezer.com` |
| TIDAL | Songs, albums, artists, playlists |
| SoundCloud | Artists, tracks, sets, `on.soundcloud.com` |
| Bandcamp | Tracks, albums |

Crosstune can't read Amazon Music links, YouTube playlists or YouTube channels, because their pages don't show the song details. It can still open songs in Amazon Music.

## 🛠️ Something wrong?

- **Links still open in another app or the browser?** Turn the service on in Crosstune's **Settings**. Then open Android's **Settings → Apps → Crosstune → Open by default**, tap **Add link**, and turn on that service's links.
- **A link opened in its own app instead?** Crosstune can't read some pages, like a SoundCloud feed, so it hands them to that service's app.
- **You got a search instead of the song?** Most apps have no public way to find a song without an account, so Crosstune searches for it. For Apple Music and Deezer, turn on **Open the exact song**.

## Privacy

- No accounts, analytics or ads.
- History stays on your device.
- To show a link's title, artist and cover, Crosstune only asks the service the link comes from, using its public page or public API.
- With **Open the exact song** on, the title and artist also go to [Apple's](https://performance-partners.apple.com/search-api) or [Deezer's](https://developers.deezer.com/api) search API.

<details>
<summary><b>Verify your download</b></summary>

Release APKs are signed with this certificate (SHA-256). Each release repeats it:

```text
64:EC:D5:52:E3:4B:B4:15:8A:04:6B:DE:1B:BD:BC:C9:04:23:9D:59:33:CF:74:C4:62:B6:FD:02:A7:B0:F6:FA
```

</details>

<details>
<summary><b>For developers</b></summary>

**Needs:** Android SDK, JDK 17+. From the command line, set `sdk.dir=/path/to/Android/Sdk` in `local.properties`.

```bash
./gradlew :app:assembleDebug   # APK in app/build/outputs/apk/debug/
./gradlew :app:ci              # exactly what CI runs
```

- `:app:ci` = Robolectric tests (100% line coverage gate) + lint + debug and minified release builds. Reports in `app/build/reports/`.
- Version comes from Git: `versionName` = latest tag without `v` (+ `-dev.N` after it). `versionCode` = commit count.

### Releasing

1. Push a tag: `git tag vX.Y.Z && git push origin vX.Y.Z` (or **Actions → Release → Run workflow**).
2. The [Release workflow](.github/workflows/release.yml) runs CI checks, then signs and publishes `Crosstune-vX.Y.Z.apk`.

Required secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

> Back up the keystore. Never replace it. Android only installs updates signed with the same key.

Store metadata lives in [`fastlane/metadata/android`](fastlane/metadata/android).

</details>
