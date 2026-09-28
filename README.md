# Crosstune

Crosstune takes a Spotify track link and opens a search for that song in YouTube Music or YouTube.

<p align="center">
  <img src="screenshots/Screenshot_20260325_002742_Crosstune.jpg" alt="Home screen" width="320">
  &nbsp;&nbsp;
  <img src="screenshots/Screenshot_20260325_002852_Crosstune.jpg" alt="Resolved track" width="320">
</p>

## What You Can Do

- Open Spotify links directly in Crosstune.
- Share a Spotify link to Crosstune from another app.
- Paste a Spotify link manually.
- Choose your default opening target: `YouTube Music` or `YouTube`.
- Copy or share the generated search query/link.

## Supported Links

- `https://open.spotify.com/track/...`
- `https://spotify.link/...`

If links keep opening in your browser, use the in-app **Open Link Settings** button and enable Crosstune for supported links.

## Install (Debug APK)

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

### Install on Device (ADB)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releasing

The [Release workflow](.github/workflows/release.yml) runs the unit tests, builds a minified release APK, signs it, and publishes it to a GitHub release as `Crosstune-vX.Y.Z.apk`. You can start it either way:

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
