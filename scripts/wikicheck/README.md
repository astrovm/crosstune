# ChordWiki browser checks

The runner reads ChordWiki's public practice editor. It submits no form and does not click a browser check. It records counts, allowed host names, resource kinds and callback decisions. It keeps no URLs, query strings, paths, cookie values, unknown host labels or editor text.

1. Back up the stopped test AVD and verify the copy before starting it. Hold its device lock. The runner needs the dev app, never the production app. Use a clean dev data set or restore the complete AVD afterwards.
2. Build with `scripts/wikicheck/build.sh`. Check both hashes and the test APK's instrumentation entry.
3. Start the emulator with `scripts/emulator/emulator.py`. Attach through `device_list` and `device_open`. Set media volume to zero through Android Settings with that pinned command before anything can play. Do not use adb to change a setting. If the command has no volume action, open Android Settings and use its sound slider.
4. Install both APKs with the SDK adb, naming the emulator serial. Save the previous `run_id` from `cache/wikicheck-status.json` if the file exists. Then start the project runner:

   ```bash
   "$HOME/Android/Sdk/platform-tools/adb" -s emulator-5580 shell am instrument -w com.astrovm.crosstune.dev.test/com.astrovm.crosstune.ChordWikiBrowserCheck
   ```

5. Wait for that file to say `ready` with a different `run_id`. Open `com.astrovm.crosstune.dev/com.astrovm.crosstune.MainActivity` through the pinned agent-device command from `device_open`. Do not use `--relaunch`, which would stop the runner.
6. Send `read` through `run-as` and the `cache/wikicheck-control` file. Wait for the same current `run_id`, a new `request_id` and `state: read`. While that request runs, its state is `reading`. Do not reuse the previous request ID. A successful editor has `editor_chars > 0` and no error. A stopped challenge is a failed editor check, even when instrumentation itself exits zero.
   The page uses the production 20-second outer deadline. `page_events` records resource decisions and HTTP errors. Require at least one `challenge_image` with decision `not_intercepted`, and none with `app_response`. A null interception result means WebView handles the resource; it does not prove network delivery or an HTTP success. Require `events_complete: true`, `events_truncated: false`, `callback_failed: false` and `pending_callbacks: 0` before counting events. The recorder installs its client and records `settings_before_load` before `loadUrl`. It seals the event list, counts and completeness flags together when the lookup ends. Later callbacks are outside this observation window. A callback still running when it closes makes that result incomplete. This runner never overrides the browser policy.
7. Change appearance through the pinned device command to recreate MainActivity. Send `read` again. `activity_creations` must increase, `live_requests` must be positive, and `old_requests` must stay zero. The runner retains the first view model's page source.
8. Send `done`. Remove the test APK. Close the Device panel, stop the emulator, restore and verify the complete AVD, delete the private backup and release the lock.

The runner stops after ten minutes. It leaves the status file for the device session to copy. App data must be restored after this check.

## DOM extraction

`test-page-script.cjs` reads the production JavaScript from `ChordWiki.kt`. It executes seven made-up DOM cases in Chrome: automatic checks, direct and shadow checkboxes, same-origin and opaque frames, an unrelated checkbox, and an editor. An opaque frame gives no evidence of a required click. It remains an unfinished check.

Run this in a network-disabled container. `Containerfile` provides the browser libraries and Node. Mount an installed Chrome read-only; it must match the host architecture. No user profile or host sockets are mounted.

```bash
nice -n 10 podman build -t localhost/crosstune-wiki-qa -f scripts/wikicheck/Containerfile .
nice -n 10 podman run --rm --network=none --memory=2g --memory-swap=2g --cpus=2 \
  -v "$PWD:/repo:ro" -v /opt/google/chrome:/chrome:ro -w /repo \
  localhost/crosstune-wiki-qa node scripts/wikicheck/test-page-script.cjs
```

`CHROME_BIN` selects the browser executable inside the container. If Chrome is missing, install it or use a machine that has it. This test does not establish Cloudflare's live challenge result. It cannot read a cross-origin frame's document.
