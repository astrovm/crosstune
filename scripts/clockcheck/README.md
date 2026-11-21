# Practice clock fixture

This API 31+ test uses a real Android media session with invented metadata. It creates no audio and makes no network request. It tests the dev app only. It does not prove that TIDAL, Spotify or YouTube Music played a line.

1. Commit the candidate, then run `scripts/device-check-build.sh PracticeClockCheck clockcheck`. The script builds the app and runner in separate container jobs. It freezes both APKs, their source commit and hashes in `build/clockcheck/`.
2. Take the project's emulator lock. Back up its stopped AVD before booting it. Start it with the project emulator script, then open it in the Device panel.
3. Install both frozen APKs with the SDK adb. Pull each installed `base.apk` and compare its hash with `sha256.txt`.
4. Run the project runner with the SDK adb:

   ```bash
   "$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" shell am instrument -w \
     com.astrovm.crosstune.dev.test/com.astrovm.crosstune.PracticeClockCheck
   ```

5. Require `result: pass`, `raw_callbacks: 4`, `transport_commands: 3`, `seek_target_ms: 43270` and `INSTRUMENTATION_CODE: 0`. Save the command result with the frozen hashes.
   Repeat with `-e output remote` before the runner component. Require `output: remote` and `seek_capabilities: true,false,true`. The default run must report `output: local` with the same capabilities. The remote run uses Android's real remote-output session and a volume provider. It sends no audio to a receiver. Both runs verify that the advertised seek action can disappear and return, and that seek, play and pause reach the selected session.
6. Shut down the emulator. Restore and verify the AVD, remove the private backup and release the lock.

The fixture uses a test-only shell permission identity to read its own media session. It restores and verifies the previous log tag and drops that identity in `finally`. Commands go to an explicit shell through standard input, so an empty property value is not stored as literal quotes. Its preferences use unique fixture names and are deleted after the run. It preserves repeated reports, a fresh update at the same position, an action-bit change and a null report. The real session receives seek, play and pause requests. No player produces sound.

The remote mode simulates the Android session boundary used by remote players. It does not run Spotify, contact Spotify Connect, or establish which actions Spotify advertises during an ad or on a real receiver. Those reports still need a real Spotify measurement.
