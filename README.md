# Crosstune

**Open any music link in your favorite app.**

Someone sends you a link from one music service, but you listen on another. With Crosstune, you tap the link and the same song opens in your favorite app. Spotify to YouTube Music, Apple Music to Deezer, TIDAL to Spotify: mix them however you like. Albums, artists and playlists work too.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Dreams Come True by S.E.S. resolved from Apple Music, ready to open in YouTube Music" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" alt="Destination choices with service icons" width="200">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Settings: default app and which services' links Crosstune opens" width="200">
</p>

## ⬇️ Install

Crosstune runs on Android 8.0 or newer.

- Download the APK from [Releases](https://github.com/astrovm/crosstune/releases).
- Or get updates automatically: add `https://github.com/astrovm/crosstune` to [Obtainium](https://github.com/ImranR98/Obtainium).

## 🚀 Set up

Setup takes about a minute.

1. Open Crosstune and pick the services people send you links from.
2. Pick the app you listen in.
3. Tap **Open link settings**, then **Add link**, tick all the links and tap **Add**.
4. If one of those services' apps is installed (Spotify, for example), Crosstune shows a button to its settings. There, choose **In your browser**, or turn off **Open supported links**. Otherwise that app keeps opening its own links.

That's it. Music links you tap now open in your favorite app.

You don't have to set up anything to paste a link into Crosstune or share one to it. That works with every service.

## How it works

Crosstune reads the link's title and artist from the service it comes from, then searches for them in your favorite app. Links you tap or share open straight away. Links you paste into Crosstune show the song and its cover first.

**Open the exact match** is enabled by default. Crosstune finds the destination link while processing the source link. **Open**, **Copy** and **Share** all use that same link, or a search link if no exact match is found. When a link needs an app choice, Crosstune shows the picker before looking for a match. Search fallbacks use a **Search in…** button.

The destination menu still says **Opens in**. A change applies to the current result. Use **Make default** to keep it for future links; per-source rules remain in Settings. The picker puts installed apps first, shows icons for every service, and includes the current item's title and artist.

## Features

- **9 services.** Crosstune reads links from Spotify, YouTube Music, YouTube, Apple Music, Deezer, TIDAL, SoundCloud and Bandcamp. It opens them in any of those, or in Amazon Music.
- **15 languages.** English, Spanish, Portuguese (Brazil), German, French, Russian, Indonesian, Turkish, Italian, Japanese, Korean, Chinese (Simplified), Hindi, Polish and Dutch. Crosstune follows your phone's language, and Android 13 or newer also lets you pick one just for Crosstune.
- **One app per service.** Send YouTube links to YouTube Music and everything else to Apple Music, for example.
- **Ask every time.** Choose the app each time you tap or share a link.
- **Exact match.** Apple Music, Deezer and YouTube Music can open the song, album or artist itself instead of a search, Bandcamp the song or album, and YouTube the song.
- **Any app or site.** Add your own, as long as it has a search page. Write `{query}` where the song name goes, like `https://example.com/search?q={query}`.
- **Shortcuts.** Paste with one tap, open or copy a recent link without replacing what's on screen, use the Quick Settings tile, or long-press the app icon to open a copied link. Recent items keep prepared destination links on your device for faster reopening, including after an app restart.

## Supported links

| Service | Links |
| --- | --- |
| Spotify | Songs, albums, artists, playlists, `spotify.link`, `spotify:` URIs, track IDs |
| YouTube Music | Songs and videos |
| YouTube | Videos, Shorts, live streams, `youtu.be` |
| Apple Music | Songs, albums, artists, playlists |
| Deezer | Songs, albums, artists, playlists, `link.deezer.com` |
| TIDAL | Songs, albums, artists, playlists |
| SoundCloud | Tracks, artists, sets, `on.soundcloud.com` |
| Bandcamp | Tracks, albums |

Crosstune can't read Amazon Music links, YouTube playlists or YouTube channels. Their pages don't include the song details. You can still pick Amazon Music as the app songs open in.

## 🛠️ Troubleshooting

**Links still open in another app or the browser.**
In Crosstune's **Settings**, turn the service on under **Open links from**. Then go to Android's **Settings → Apps → Crosstune → Open by default**, tap **Add link**, and turn on that service's links.

**A link opened in its own app.**
Some pages, like a SoundCloud feed, aren't a song, album, artist or playlist. Crosstune can't read those, so it passes them to that service's app.

**I got a search, not the song.**
Most apps don't offer a public way to find a song without an account, so Crosstune searches for it by title and artist. Apple Music, Deezer, YouTube Music, YouTube and Bandcamp support exact matching, which is enabled by default. If you turned it off, turn on **Open the exact match**. When no confident match is available, Crosstune uses a search link.

## Privacy

- No account, no analytics, no ads.
- Your history stays on your device.
- To show a link's title, artist and cover, Crosstune asks only the service the link comes from, through its public page or public API. Covers load from that service's image servers.
- With **Open the exact match** on, the title and artist are also sent to the search of the app you open it in: [Apple's](https://performance-partners.apple.com/search-api) or [Deezer's](https://developers.deezer.com/api) API, or the website search behind YouTube Music, YouTube and Bandcamp.

<details>
<summary><b>Verify your download</b></summary>

Release APKs are signed with this certificate. Its SHA-256 fingerprint is also listed on each release:

```text
64:EC:D5:52:E3:4B:B4:15:8A:04:6B:DE:1B:BD:BC:C9:04:23:9D:59:33:CF:74:C4:62:B6:FD:02:A7:B0:F6:FA
```

</details>

<details>
<summary><b>Building from source</b></summary>

You need the Android SDK and JDK 17 or newer. When building from the command line, set `sdk.dir=/path/to/Android/Sdk` in `local.properties`.

```bash
./gradlew :app:assembleDebug   # APK in app/build/outputs/apk/debug/
./gradlew :app:ci              # the same checks CI runs
```

`:app:ci` runs the Robolectric tests with a 100% line coverage gate, lint, and the debug and minified release builds. Reports go to `app/build/reports/`.

The version is set explicitly in `app/build.gradle.kts`: `versionName` is `X.Y.Z`, and `versionCode` is `X*1000000 + Y*10000 + Z*100`. Builds use these values even without Git history or tags. F-Droid's update checker reads the same metadata.

### Releasing

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, and add the changelog under `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Commit and merge these changes before releasing.
2. Push a tag with `git tag vX.Y.Z && git push origin vX.Y.Z`, or run **Actions → Release → Run workflow**.
3. The [Release workflow](.github/workflows/release.yml) runs the CI checks, verifies that the APK's version matches the tag, then signs and publishes `Crosstune-vX.Y.Z.apk`.
4. For Google Play, download the signed App Bundle `Crosstune-vX.Y.Z.aab` from the workflow run's **Crosstune-vX.Y.Z-play** artifact and upload it in Play Console. It's signed with the same key, which Play uses as the upload key.

It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Output of `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

> Back up the keystore and never replace it. Android only installs an update if it's signed with the same key.

Store listing text lives in [`fastlane/metadata/android`](fastlane/metadata/android).

</details>
