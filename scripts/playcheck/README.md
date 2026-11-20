# Playback checks

`golden.sh` records the main branch's answers to the scenes in `PlayScenes.kt`. `PlayGoldenTest` compares the current answers with that file and its explicit correction rules.

## Sessions without an artist

`ArtistlessPlaybackCheck` creates temporary Android media sessions with invented names. It checks the real session selection, follower and transport controls. It makes no sound, opens no music app and sends no network request. A matching session must receive seek and pause. A different title must receive neither command.

1. Hold the test device lock. Back up the stopped AVD and verify the copy before starting it. Restore that copy after the check.
2. Build the dev APK with `scripts/gradle-box/gradle-box.sh :app:assemblePlayDebug`. Build the test APK separately with `scripts/gradle-box/gradle-box.sh :app:assemblePlayDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.ArtistlessPlaybackCheck`.
3. Verify both APK hashes. Install them with the SDK adb and an explicit test-device serial. The runner refuses the production package.
4. Open the dev app with the pinned command from `device_open`. Allow notification access through Android's settings. Set media volume to zero.
5. Run the fixture. Set `ANDROID_SERIAL` to that test device first:

   ```bash
   "$HOME/Android/Sdk/platform-tools/adb" -s "$ANDROID_SERIAL" shell am instrument -w \
     com.astrovm.crosstune.dev.test/com.astrovm.crosstune.ArtistlessPlaybackCheck
   ```

6. Read each JSON case. `selected`, `followed` and `controls` must agree with `expected`. Require all fourteen `passed` fields and `INSTRUMENTATION_CODE: 0`. A baseline run that selects a partial title must fail.
7. The runner releases every session in `finally`. Remove the test APK, close the Device panel, stop the emulator, restore and verify the AVD, delete the private backup and release the lock.

The runner tests Android's session APIs. It does not prove how each music app fills its metadata fields.
