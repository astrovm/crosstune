# AGENTS.md

## Purpose

This repository contains an Android app (`Crosstune`) that converts Spotify track links into YouTube Music searches.

Primary flow:
1. Accept Spotify track URL (deep link or pasted URL).
2. Resolve track metadata from public Spotify track page metadata (no Spotify API token).
3. Open YouTube Music search for track + artist.

## Project Layout

- `app/src/main/java/com/astrovm/crosstune/MainActivity.kt`: main app flow and UI entry point.
- `app/src/main/java/com/astrovm/crosstune/ui/theme/*`: Compose theme setup.
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

Preferred local Android toolchain discovery (user-agnostic):
- Treat Android SDK and Android Studio as user-home installations by default:
  - `ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}`
  - `ANDROID_STUDIO_HOME=${ANDROID_STUDIO_HOME:-$HOME/android-studio}`
- Add commonly needed binaries to `PATH` when using CLI tooling (when present):
  - `$ANDROID_SDK_ROOT/platform-tools`
  - `$ANDROID_SDK_ROOT/cmdline-tools/latest/bin`
  - `$ANDROID_STUDIO_HOME/jbr/bin` (for Studio-bundled Java when needed)
- Prefer env vars and `$HOME`-relative paths in commands/docs; avoid hard-coding a specific username path.

## Development Rules

- Keep package name and application ID as `com.astrovm.crosstune` unless explicitly requested.
- Preserve deep-link behavior for `https://open.spotify.com/track/*`.
- Keep UI in Compose (do not migrate to XML views unless requested).
- Use `res_clean` for active resources unless the Gradle source set is changed intentionally.
- Do not commit local machine files (`local.properties`, IDE caches, build outputs).

## Reliability Notes

- Metadata parsing depends on Spotify page meta tags (`og:description`, `og:title`).
- If parsing fails after Spotify markup changes, update parsing logic in `MainActivity.kt`.

## Change Checklist

Before finishing changes:
1. Ensure code compiles with `./gradlew :app:assembleDebug`.
2. Keep behavior unchanged unless the task requests a behavior change.
3. Update `README.md` when setup, architecture, or commands change.
