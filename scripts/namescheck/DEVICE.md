# Recording-name check on Android

This checks the installed app's real `MediaSessionPlayback.follow` against VLC's real media session. It uses invented local tones. It does not contact a song service or send playback controls. The activity UI is not part of this probe.

1. Back up the complete stopped test AVD and its `.ini` before installation. Verify the backup. Keep the baseline private.
2. Build main and the candidate separately with `scripts/namescheck/device-build.sh COMMIT LABEL`. Each output in `build/names-device/LABEL/ready.json` records the app commit, probe source hash and copied APK hashes. The probe is overlaid on main without changing its production code. The temporary non-agent baseline checkout is deleted at exit. A clean current candidate builds in its own checkout to reuse its native build cache. The script refuses a changed commit or uncommitted candidate.
3. Use the Device panel and its pinned `agent-device` for every launch, tap, permission, setting and screenshot. Install the two frozen APKs with the SDK adb, targeted to the locked emulator. Verify the copied and installed app hashes.
4. Grant the dev app notification access through Android Settings. Set media volume to zero. Leave the network disconnected. Do not open Crosstune's real Recent list.
5. Run `python3 scripts/namescheck/recording_fixtures.py build/names-device/fixtures`. Push these invented files and open their playlist in VLC through the Device panel.
6. The project instrumentation command is:

   ```bash
   adb -s SERIAL shell "am instrument -w -e asked 'Paperkite (Live)' -e playing 'Paperkite Meadow (Live)' -e matches false com.astrovm.crosstune.dev.test/com.astrovm.crosstune.RecordingNameCheck"
   ```

   Use the SDK adb. `playing` must exactly match VLC's raw title, or the probe fails before observing. It accepts only the listed fixture titles. It must emit an observation. Code 0 and `result=pass` mean the observed match agrees with `matches`. Code 1 with `result=wrong_match` is the original failure, not a runner crash.
7. On main and the candidate, measure both directions of the ASCII Live pair. On the candidate, also measure the full-width Live pair, the two Japanese Cover titles, and a positive exact-name control after each negative case. Check the untagged `01 Live Forever.mp3` filename against `Live Forever` with `matches=true`. Use the exact raw title VLC reports. Also reject the English Version pair and retain `Piano Paperkite` against `The Piano Paperkite`. The wrong-name cases must reproduce on main and disappear on the candidate. Positive controls must stay positive.
8. Read crash and ANR counts for the dev app. Save only fake-content screenshots and the probe output. Do not dump other media sessions, private files, tokens or their hashes.
9. Stop VLC, clear only fixture history and its queue, remove the fixtures and test APK, close the Device panel and stop the emulator. Restore the full AVD while stopped, verify it against the private backup, then delete the backup and release the lock.

Limits: one Android device, one real local player, and the app's follower API. This does not measure live Apple or YouTube services. The unit integration regressions cover service selection and the YouTube queue with invented HTTP responses.
