# Offline chord fixture

This project test runner supplies fake responses for one invented song, Paper Lantern Sky by The Fixture Owls. Ultimate Guitar fails until recovery. The other chord sites answer with no sheets. The main activity uses only intercepted OkHttp requests. Disconnect Wi-Fi and mobile data before the run. The runner rejects an active device network, so independent widget clients cannot reach external servers. The runner does not launch an activity or drive the UI.

Run `scripts/chordcheck/build.sh` to build the dev APK and test APK separately. It records their hashes and commit in `build/chordcheck/`.

Lock the test device first. Back up the dev app's complete private data and device settings. Keep the backup private and out of git. Redact tokens and token hashes from manifests and output. The runner replaces the dev app's preferences, Recent, caches and pinned offline data with an isolated fixture. It refuses to run against the production app. Normal app use also changes caches and offline data. Restore all app data after the test.

Use the pinned device UI to disconnect device networks. Record and restore their initial settings. Install both APKs. Run with the Android SDK adb:

```
adb shell am instrument -w com.astrovm.crosstune.dev.test/com.astrovm.crosstune.OfflineChordCheck
```

The runner sets its factories before writing `cache/chordcheck-status.json`. Read that JSON with `adb exec-out run-as com.astrovm.crosstune.dev cat cache/chordcheck-status.json`. It contains only fixture service names and request counts.

After status is ready, use T3 `device_open` and its pinned `agent-device` command for every UI action. Open the dev app, open Recent, tap the fake song's title and choose Keep offline. Check the pending state. Wait for `offline_failed_complete=true` in the status JSON. This requires a pending offline entry and a completed offline fetch round after the empty queue. A request count alone can belong to the song card. The runner rejects recovery before this condition holds.

Use the SDK only to change the backend fixture data:

```
adb shell 'run-as com.astrovm.crosstune.dev sh -c "echo recover > cache/chordcheck-control"'
```

Recovery makes the chord service answer and emits a new unmetered network event. Check that the actual UI reaches Kept offline. Take before and after screenshots. Then write `done` to the same control file. The runner removes its control and status files and restores factory lambdas before it exits. It stops with a failure after three minutes if no done command arrives.

After the run, stop the app through the device UI or a project reinstall, restore its complete private data, and compare redacted manifests. Remove the test APK and fixture files. Restore settings, release the lock and close the Device panel. Remove private backups after verification. No fixture belongs in production app data.

## Real default-network handover

Use `am instrument -w -e network real` with the same runner to observe Android's real connection flow. Start with device networks disconnected, as above. This mode still intercepts every main-activity HTTP request and uses the same invented offline song. After the status file is ready, connect one unmetered network through the pinned device UI. Open the app and keep the fixture song offline.

Wait for `offline_failed_complete=true`. Write `recover` to make the fake backend answer. In this mode, that command does not emit a network event. Switch the default to another unmetered network through the device UI. Keep the app in front, so an activity restart cannot create the retry. Check that the song becomes kept and that its offline round increases once.

The status JSON includes timestamped `network_events`: real default callbacks, network IDs, internet and unmetered flags, and values emitted by the app's connection flow. It omits network names and addresses. Confirm both networks are unmetered. If a `false` event occurs between them, the run checks recovery but does not reproduce the dropped `true`-after-`true` case. Real-network mode has a fifteen-minute manual-run deadline. Write `done`, then restore the complete device baseline.

On a backed-up Android 10 or newer emulator, the second network can be the test APK's VPN. First open its `com.astrovm.crosstune.FixtureVpnConsent` activity through the pinned device CLI and accept Android's VPN dialog. Do this before starting the runner. Then disconnect networks, start real-network mode, enable Wi-Fi, and keep the invented song offline as above. After the failed fetch finishes, write `recover`, then `vpn`, to the control file. The second command starts a VPN limited to the dev app, with no traffic forwarding. It uses Android's real VPN network and callback, not a fake connection value. The runner stops it on exit. Restore the entire stopped AVD afterward. The fixture also replaces the hidden ChordWiki page reader with an empty fake page, so no WebView bypasses HTTP interception.
