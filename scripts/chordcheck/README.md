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
