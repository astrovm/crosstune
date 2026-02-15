# AGENTS.md

## Purpose

This repository contains an Android app (`Music URL Handler`) that converts Spotify track links into YouTube Music searches.

Primary flow:
1. Accept Spotify track URL (deep link or pasted URL).
2. Resolve track metadata from Spotify API.
3. Open YouTube Music search for track + artist.

## Project Layout

- `app/src/main/java/com/example/musicurlhandler/MainActivity.kt`: main app flow and UI entry point.
- `app/src/main/java/com/example/musicurlhandler/network/SpotifyService.kt`: Retrofit API interface.
- `app/src/main/java/com/example/musicurlhandler/model/TrackResponse.kt`: API response models.
- `app/src/main/java/com/example/musicurlhandler/ui/theme/*`: Compose theme setup.
- `app/src/main/res_clean/*`: active app resources used by Gradle build.
- `app/src/main/res/*`: extracted/reference resources; not used by current build source set.

## Build And Run

- Build debug APK:
  - `./gradlew :app:assembleDebug`
- APK output:
  - `app/build/outputs/apk/debug/app-debug.apk`

If your system default Java is too new for this Gradle/Kotlin setup, use JDK 21 explicitly:
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug`

For local CLI builds, `local.properties` may be required:
- `sdk.dir=/path/to/Android/Sdk`

## Development Rules

- Keep package name and application ID as `com.example.musicurlhandler` unless explicitly requested.
- Preserve deep-link behavior for `https://open.spotify.com/track/*`.
- Keep UI in Compose (do not migrate to XML views unless requested).
- Use `res_clean` for active resources unless the Gradle source set is changed intentionally.
- Do not commit local machine files (`local.properties`, IDE caches, build outputs).

## Security Notes

- `MainActivity.kt` currently contains Spotify client credentials from reconstructed code.
- Do not add new hardcoded secrets.
- Prefer moving credentialed token exchange to a backend service for production changes.

## Change Checklist

Before finishing changes:
1. Ensure code compiles with `./gradlew :app:assembleDebug`.
2. Keep behavior unchanged unless the task requests a behavior change.
3. Update `README.md` when setup, architecture, or commands change.
