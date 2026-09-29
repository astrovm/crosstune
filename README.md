# Crosstune

**Open any music link in the app you actually use.**

A friend sends a Spotify link but you use YouTube Music? Tap it, and Crosstune opens the same song there. It works with songs, albums, artists and playlists across eight services.

<p align="center">
  <img src="screenshots/home.png" alt="A Spotify link resolved to its song, ready to open in YouTube Music" width="200">
  <img src="screenshots/recent.png" alt="The result with recent links and their covers" width="200">
  <img src="screenshots/settings.png" alt="Settings: default app and which services' links Crosstune opens" width="200">
  <img src="screenshots/setup.png" alt="First-run setup: choosing which links Crosstune opens" width="200">
</p>

## Download

Get the latest `Crosstune-vX.Y.Z.apk` from [Releases](https://github.com/astrovm/crosstune/releases), or add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates. Requires Android 8.0 or later.

> **Updating from v1.0.6 or earlier?** Newer releases are signed with a different key, so uninstall the old version once before installing.

## Getting started

1. Open Crosstune. A short setup asks which services' links it should open (apps you have installed are listed first) and where to send them.
2. Tap **Open link settings**, choose **Add link**, and select the links setup lists for you. Android requires this once per service.
3. That's it. Tap a music link anywhere and it opens in your app.

You can also paste a link into Crosstune, or share one to it from any app.

## Features

- **Eight services in, nine out.** Read links from Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud and Bandcamp, and open them in any of those or in Amazon Music.
- **Your rules.** Pick a default app, or send each service somewhere different, like YouTube videos to YouTube Music and everything else to Apple Music.
- **Ask every time.** Turn on **Ask where to open shared links** to choose an app for each link.
- **Exact matches.** Turn on **Open exact matches when possible** to open the exact item in Apple Music or Deezer instead of a search.
- **Custom destinations.** Add any app or website that has a search URL, like `https://example.com/search?q={query}`.
- **Quick access.** Paste with one tap, reopen recent links with their covers, or use the **Open copied music link** Quick Settings tile and launcher shortcut.
- **Always a way out.** Open a result, or a link Crosstune couldn't read, in the app it came from. Copy the search text or share a search link.

## Supported links

| Service | Links Crosstune understands |
| --- | --- |
| Spotify | Songs, albums, artists and playlists on `open.spotify.com`, `spotify.link` short links, `spotify:` URIs, bare track IDs |
| YouTube Music | `music.youtube.com/watch?v=…` |
| YouTube | Videos on `youtube.com` and `youtu.be`, Shorts and live streams |
| Apple Music | Songs, albums, artists and playlists on `music.apple.com` |
| Deezer | Songs, albums, artists and playlists on `deezer.com`, `link.deezer.com` short links |
| TIDAL | Songs, albums, artists and playlists on `tidal.com` and `listen.tidal.com` |
| SoundCloud | Artists, tracks and sets on `soundcloud.com`, `on.soundcloud.com` short links |
| Bandcamp | Tracks and albums on `*.bandcamp.com` |

Amazon Music works as a destination only, because its pages don't expose song details. YouTube playlists and channels aren't supported for the same reason.

## Troubleshooting

- **Links still open in another app or the browser.** Make sure the service is turned on in Crosstune's **Settings**, then open Android's **Settings → Apps → Crosstune → Open by default**, tap **Add link** and select that service's links.
- **A link opens in the service's own app.** Pages Crosstune can't convert, like a SoundCloud feed, are handed straight to that app.
- **A song opens as a search.** Most apps have no public way to look up a song without an account, so Crosstune opens a search. For Apple Music and Deezer, turn on **Open exact matches when possible**.

## Privacy

No accounts, analytics or ads. To show a link's title, artist and cover, Crosstune asks the service the link belongs to, using its public page or official key-less API (YouTube and SoundCloud oEmbed, the iTunes Lookup API, the Deezer API). With exact matches on, it also sends the title and artist to [Apple's iTunes Search API](https://performance-partners.apple.com/search-api) or [Deezer's API](https://developers.deezer.com/api). History never leaves your device.

## Verify your download

Release APKs are signed with this certificate. Each release's notes repeat the fingerprint (SHA-256):

```text
64:EC:D5:52:E3:4B:B4:15:8A:04:6B:DE:1B:BD:BC:C9:04:23:9D:59:33:CF:74:C4:62:B6:FD:02:A7:B0:F6:FA
```

## For developers

You need the Android SDK and JDK 17 or later. If you build from the command line, point `local.properties` at the SDK with `sdk.dir=/path/to/Android/Sdk`.

```bash
./gradlew :app:assembleDebug   # APK in app/build/outputs/apk/debug/
./gradlew :app:ci              # exactly what CI runs
```

`:app:ci` runs the Robolectric unit tests with a 100% line coverage gate, lint, and the debug and minified release builds. Reports end up in `app/build/reports/`.

The version comes from Git: `versionName` is the latest tag without the `v` (plus `-dev.N` for commits after it) and `versionCode` is the total commit count.

### Releasing

Push a tag (`git tag vX.Y.Z && git push origin vX.Y.Z`), or run **Actions → Release → Run workflow** with a tag name. The [Release workflow](.github/workflows/release.yml) runs the same checks as CI, then builds, signs and publishes `Crosstune-vX.Y.Z.apk` to a GitHub release.

It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Back up the keystore and never replace it: Android only installs updates signed with the same key.

Store listing metadata for F-Droid and similar catalogs lives in [`fastlane/metadata/android`](fastlane/metadata/android).
