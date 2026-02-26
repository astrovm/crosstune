# Crosstune

Crosstune takes a Spotify track link and opens a search for that song in YouTube Music or YouTube.

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

## Notes

- The app uses public Spotify page metadata (no Spotify API key needed).
- If Spotify changes their page metadata format, some links may stop resolving until updated.
