# Practice reminder timing

This harness measures the dev app on the shared Samsung SM-S908E. It is under development. Check its output and recovery on the phone before using its numbers.

Build and install the dev APK first. Take the Samsung lock for 45 minutes. Then run one measurement:

```sh
python3 scripts/devicelock.py take samsung --who 'issue 773' --minutes 45
python3 scripts/reminder-lateness/measure.py --minutes 20 --wait-minutes 15
```

Add `--exact` to allow exact alarms for the run. Add `--force-idle` to force Doze. The lead and wait must total at most 38 minutes. The script releases the lock after the run. Take it again for the next run.

The script uses a fake due card. It records the alarm window and the notification creation time. It discards a run if the screen wakes. No notification by the end of the wait is an unobserved delivery, not a lateness value.

The script temporarily changes the dev app's exact-alarm and notification access and simulates an unplugged battery. It restores the original settings and preferences. It also restores forced Doze and the screen state when needed. It never changes media volume or Do Not Disturb. It refuses to run unless media volume is zero.

A private recovery journal and preference backup go in `/mnt/data/agent-cache/crosstune-773/` and are deleted after successful restoration. If restoration fails, take the Samsung lock again and run:

```sh
python3 scripts/reminder-lateness/measure.py --recover
```

Do not delete the recovery files until restoration succeeds. The journal does not contain the device address.

Run the fake-data tests in a container:

```sh
podman run --rm --network none --memory 1g --memory-swap 1g -v "$PWD/scripts/reminder-lateness:/work:ro" -w /work python:3.13-slim python -B -m unittest -v test_measure
```
