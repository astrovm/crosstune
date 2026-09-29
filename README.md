# Crosstune

**Tap any music link. It opens in *your* app.**

Friend sends Spotify, you use YouTube Music? Crosstune opens the same song there. Songs, albums, artists, playlists.

<p align="center">
  <img src="screenshots/home.png" alt="A Spotify link resolved to its song, ready to open in YouTube Music" width="200">
  <img src="screenshots/recent.png" alt="The result with recent links and their covers" width="200">
  <img src="screenshots/settings.png" alt="Settings: default app and which services' links Crosstune opens" width="200">
  <img src="screenshots/setup.png" alt="First-run setup: choosing which links Crosstune opens" width="200">
</p>

## Get it

- **APK:** [Releases](https://github.com/astrovm/crosstune/releases)
- **Auto-updates:** add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium)
- **Needs:** Android 8.0+

> **Coming from v1.0.6 or older?** Uninstall once first. The signing key changed.

## Set up (1 minute)

1. Open Crosstune. Pick your links and your app.
2. Tap **Open link settings** → **Add link** → select the links shown.
3. Done. Tap a music link anywhere.

Also works by pasting or sharing a link to Crosstune.

## What it does

- **8 in, 9 out**: Reads Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud, Bandcamp. Opens in any of them, or Amazon Music.
- **Per-service rules**: e.g. YouTube → YouTube Music, everything else → Apple Music.
- **Ask every time**: Pick an app for each link.
- **Exact matches**: Apple Music and Deezer open the exact item, not a search.
- **Custom apps**: Any search URL, like `https://example.com/search?q={query}`.
- **Fast access**: One-tap paste, recent links, Quick Settings tile, launcher shortcut.

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

Not supported: Amazon Music links, YouTube playlists and channels. Their pages don't show song details.

## Something wrong?

- **Links open somewhere else?** Turn the service on in Crosstune **Settings**. Then Android **Settings → Apps → Crosstune → Open by default → Add link**.
- **Opened in the original app?** Crosstune can't convert that page (like a SoundCloud feed), so it passes it on.
- **Got a search, not the song?** Most apps have no public lookup. Turn on **Exact matches** for Apple Music and Deezer.

## Privacy

- No accounts, analytics or ads.
- History stays on your device.
- To read a link's title, artist and cover, Crosstune asks only that link's service (public page or key-less API).
- With **Exact matches** on, the title and artist also go to [Apple's](https://performance-partners.apple.com/search-api) or [Deezer's](https://developers.deezer.com/api) search API.

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
