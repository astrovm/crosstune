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

## Native playback evidence

The dev app writes Practice's native playback evidence to logcat under the `CrosstuneClock` tag, when the tag's level is DEBUG. CONTRIBUTING.md says what the records hold and what they never hold.

Use the SDK's adb and name the device with `ANDROID_SERIAL`. Set `ANDROID_HOME` to the SDK directory first. Save the previous tag level so it can be restored. In one terminal:

```bash
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the test device}"
adb="$ANDROID_HOME/platform-tools/adb"
previous_clock_tag=$("$adb" shell getprop log.tag.CrosstuneClock)
[[ $previous_clock_tag =~ ^[[:alnum:]]*$ ]] || { printf 'Unexpected diagnostic tag value\n' >&2; exit 1; }
"$adb" shell setprop log.tag.CrosstuneClock DEBUG
mkdir -p build/practice-clock
"$adb" logcat -v raw -s CrosstuneClock:D '*:S' > build/practice-clock/device.ndjson
```

Stop logcat with Ctrl+C, then restore the level in that same terminal:

```bash
"$adb" shell "setprop log.tag.CrosstuneClock '$previous_clock_tag'"
```

Keep these device captures out of git. An agent uses the device tools for all app launches, taps, appearance changes and other UI work. The adb commands above only configure the project diagnostic and read its log.

Each line is a JSON record. `observed_ms` uses the app's monotonic clock since boot, as do Android's playback update times. Speed is text so malformed non-finite values remain representable. Match records by `app` and `session`; `attempt` separates native Practice attempts. `observer` identifies each follower subscription. Several followers can receive the same Android report, so count repeats within one observer. A raw `session` is null until normal playback assigns its number; diagnostics never allocate control-session numbers or update their cache order.

| Event | Meaning |
|---|---|
| `follow_begin`, `follow_end` | The lifetime of one follower subscription. A queued late callback keeps its original observer number. |
| `media_report` | The raw callback argument, before clock correction and flow deduplication. Repeated, null and negative-position reports stay visible. `updated_ms` is the app's own report time. |
| `followed_clock` | The clock after timing corrections, or `present=false` if no usable clock matched. The salted `item_hash` ties reports to the same metadata within one dev app process without recording song names or IDs. |
| `media_command` | The session about to receive a seek, play or pause transport call. It is a request, not an acknowledgement. |
| `card_clock` | The clock Practice actually observes while reaching or playing a line. Polls can repeat; they are not new Android callbacks. `projected_ms` is the clock projected to the observation time. |
| `candidate`, `native_result` | The native session's initial capability/window decision and the final native attempt outcome. |
| `seek_request` | Practice's target and requested playing state, with the time used to judge later reports. |
| `reach_result`, `play_result` | Whether the line was reached or played, and why that phase ended. Cancellation is explicit. |
