# Music URL Handler

Android app that accepts Spotify track links and opens the equivalent search in YouTube Music.

## What It Does

- Handles `https://open.spotify.com/track/...` links via Android deep links.
- Lets you paste a Spotify track URL manually in the app UI.
- Fetches track metadata from Spotify (`name` + `artists`) and opens:
  - `https://music.youtube.com/search?q=<track + artist>`

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- OkHttp
- Retrofit + Gson
- Android Gradle Plugin `8.7.2`
- Kotlin `2.0.21`

## Project Structure

```text
.
├── app
│   ├── src/main/java/com/example/musicurlhandler
│   │   ├── MainActivity.kt
│   │   ├── model/TrackResponse.kt
│   │   ├── network/SpotifyService.kt
│   │   └── ui/theme/*
│   ├── src/main/AndroidManifest.xml
│   └── src/main/res_clean/*
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## Requirements

- JDK 17+ (project compiles with Java 17 target)
- Android SDK (API 34)
- Android Studio (recommended) or CLI Gradle build

If building from CLI, ensure `local.properties` includes your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

```bash
./gradlew :app:assembleDebug
```

Output APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Install the debug APK on a device/emulator.
2. Open the app and paste a Spotify track URL, or open a Spotify track link directly on the device.
3. The app resolves the track and opens YouTube Music search for it.

## Notes

- This repository was reconstructed from an APK artifact.
- Current implementation includes Spotify client credentials directly in `MainActivity.kt`.
  - For production, move credentials and token exchange to a secure backend.
