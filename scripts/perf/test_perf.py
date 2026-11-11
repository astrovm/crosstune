"""Runs perf.py against a stand-in adb that says what it was asked and answers as a phone does."""
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import perf  # noqa: E402

GFXINFO = """Applications Graphics Acceleration Info:
Total frames rendered: 1000
Janky frames: 19 (1.90%)
Janky frames (legacy): 40 (4.00%)
50th percentile: 5ms
90th percentile: 9ms
95th percentile: 11ms
99th percentile: 14ms
Number Missed Vsync: 2
Number High input latency: 3
Number Slow UI thread: 4
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 6
HISTOGRAM: 5ms=10
GPU HISTOGRAM:
GPU 50th percentile: 3ms
"""

# The stat lines of the app and its main thread, before and after: the name in brackets holds a
# space and a bracket, as a thread's can. utime + stime is fields 14 and 15.
STAT = "123 (m.cross tune)) S" + " 0" * 10 + " {u} {s}" + " 0" * 30

FAKE_ADB = r"""#!/bin/sh
echo "$*" >> "$FAKE/asked"
case "$*" in
  "install -r -d "*.fail) echo "Failure [INSTALL_FAILED_INVALID_APK]" ;;
  "install -r -d "*) echo Success ;;
  "shell wm size") printf 'Physical size: 1080x2316\nOverride size: 720x1544\n' ;;
  "shell dumpsys thermalservice") cat "$FAKE/thermal" ;;
  "shell dumpsys battery") printf 'Current Battery Service state:\n  temperature: 312\n' ;;
  "shell dumpsys package com.astrovm.crosstune.dev") printf 'pkgFlags=[ %s HAS_CODE ]\nx\n' "$(cat "$FAKE/flags")" ;;
  "shell dumpsys gfxinfo com.astrovm.crosstune.dev") cat "$FAKE/gfxinfo" ;;
  "shell am start -W "*) printf 'Status: ok\nTotalTime: 812\n' ;;
  "shell pidof com.astrovm.crosstune.dev") echo 123 ;;
  "shell cat /proc/123/stat /proc/123/task/123/stat")
    n=$(cat "$FAKE/cats"); echo $((n + 1)) > "$FAKE/cats"
    if [ "$n" = 0 ]; then printf '%s\n%s\n' "$STAT_APP_0" "$STAT_MAIN_0"; else printf '%s\n%s\n' "$STAT_APP_1" "$STAT_MAIN_1"; fi ;;
esac
"""


class PerfTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        home = Path(self.home.name)
        tools = home / "platform-tools"
        tools.mkdir()
        adb = tools / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        (home / "gfxinfo").write_text(GFXINFO)
        (home / "thermal").write_text("Thermal Status: 0\n")
        (home / "flags").write_text("")
        (home / "cats").write_text("0")
        self.home_path = home
        self.env = mock.patch.dict(os.environ, {
            "ANDROID_HOME": str(home), "FAKE": str(home),
            "STAT_APP_0": STAT.format(u=100, s=50), "STAT_MAIN_0": STAT.format(u=10, s=5),
            "STAT_APP_1": STAT.format(u=500, s=250), "STAT_MAIN_1": STAT.format(u=60, s=15),
        })
        self.env.start()
        self.slept = []
        self.sleep = mock.patch.object(perf.time, "sleep", self.slept.append)
        self.sleep.start()

    def tearDown(self):
        self.sleep.stop()
        self.env.stop()
        self.home.cleanup()

    def run_perf(self, *argv):
        out = io.StringIO()
        with redirect_stdout(out):
            perf.main(list(argv))
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def asked(self):
        return (self.home_path / "asked").read_text().splitlines()

    def test_jank_reads_the_frames_since_the_reset_and_sums_up_each_build(self):
        lines = self.run_perf("jank", "--runs", "2", "--label", "main")
        run = lines[0]
        self.assertEqual(
            {"label": "main", "run": 1, "frames": 1000, "janky": 19, "janky_pct": 1.9, "p50_ms": 5, "p90_ms": 9, "p99_ms": 14,
             "slow_ui": 4, "slow_draw": 6, "missed_vsync": 2, "input_latency": 3, "thermal": "0", "battery_c": 31.2},
            run,
        )
        self.assertEqual({"label": "main", "runs": 2, "frames": {"mean": 1000, "sd": 0.0, "min": 1000, "max": 1000},
                          "janky_pct": {"mean": 1.9, "sd": 0.0, "min": 1.9, "max": 1.9},
                          "p90_ms": {"mean": 9, "sd": 0.0, "min": 9, "max": 9},
                          "p99_ms": {"mean": 14, "sd": 0.0, "min": 14, "max": 14},
                          "slow_ui": {"mean": 4, "sd": 0.0, "min": 4, "max": 4}}, lines[2])
        asked = self.asked()
        # Swiped on the screen's own size, the override, up then down, 8 flings each way in each of 2 runs.
        self.assertEqual(32, asked.count("shell input swipe 360 1158 360 514 150") + asked.count("shell input swipe 360 514 360 1158 150"))
        self.assertLess(asked.index("shell input swipe 360 1158 360 514 150"), asked.index("shell input swipe 360 514 360 1158 150"))
        reset = asked.index("shell dumpsys gfxinfo com.astrovm.crosstune.dev reset")
        self.assertLess(asked.index("shell am force-stop com.astrovm.crosstune.dev"), reset)
        self.assertLess(reset, asked.index("shell input swipe 360 1158 360 514 150"))

    def test_jank_without_numbers_says_none(self):
        (self.home_path / "gfxinfo").write_text("No process found\n")
        run = self.run_perf("jank", "--runs", "1")[0]
        self.assertEqual((0, 0, 0.0, 0), (run["frames"], run["janky"], run["janky_pct"], run["p90_ms"]))

    def test_builds_take_turns_and_one_not_debuggable_is_compiled_from_its_profile(self):
        lines = self.run_perf("start", "--runs", "2", "--build", "old=a.apk", "--build", "new=b.apk")
        self.assertEqual(["old", "new", "old", "new"], [line["label"] for line in lines[:4]])
        self.assertEqual(812, lines[0]["total_ms"])
        self.assertEqual([{"label": "old", "runs": 2, "total_ms": {"mean": 812, "sd": 0.0, "min": 812, "max": 812}},
                          {"label": "new", "runs": 2, "total_ms": {"mean": 812, "sd": 0.0, "min": 812, "max": 812}}], lines[4:])
        asked = self.asked()
        self.assertEqual(["install -r -d a.apk", "install -r -d b.apk"] * 2, [line for line in asked if line.startswith("install")])
        self.assertEqual(4, asked.count("shell cmd package compile -f -m speed-profile com.astrovm.crosstune.dev"))

    def test_a_debuggable_build_runs_as_it_is(self):
        (self.home_path / "flags").write_text("DEBUGGABLE")
        self.run_perf("start", "--runs", "1", "--build", "dev=a.apk")
        self.assertFalse([line for line in self.asked() if "compile" in line or "broadcast" in line])

    def test_a_failed_install_stops(self):
        with self.assertRaises(SystemExit) as stopped:
            self.run_perf("start", "--runs", "1", "--build", "x=missing.apk.fail")
        self.assertIn("missing.apk.fail", str(stopped.exception))

    def test_cpu_is_ms_a_second_for_the_main_thread_and_the_whole_app(self):
        run = self.run_perf("cpu", "--runs", "1", "--seconds", "10")[0]
        # Main: 75 - 15 ticks of 10 ms in 10 s; app: 750 - 150.
        self.assertEqual((60.0, 600.0), (run["main_ms_per_s"], run["app_ms_per_s"]))
        self.assertIn(10.0, self.slept)

    def test_it_waits_while_the_phone_is_throttled(self):
        thermal = self.home_path / "thermal"
        thermal.write_text("Thermal Status: 2\n")
        self.sleep.stop()
        self.sleep = mock.patch.object(perf.time, "sleep", lambda seconds: (self.slept.append(seconds), thermal.write_text("Thermal Status: 0\n")))
        self.sleep.start()
        self.run_perf("start", "--runs", "1", "--settle", "1")
        self.assertEqual([10, 1], self.slept)

    def test_it_runs_at_a_status_it_is_allowed(self):
        (self.home_path / "thermal").write_text("Thermal Status: 1\n")
        self.run_perf("start", "--runs", "1", "--settle", "1", "--status", "1")
        self.assertEqual([1], self.slept)

    def test_it_waits_while_the_phone_says_no_status(self):
        thermal = self.home_path / "thermal"
        thermal.write_text("nothing\n")
        self.sleep.stop()
        self.sleep = mock.patch.object(perf.time, "sleep", lambda seconds: (self.slept.append(seconds), thermal.write_text("Thermal Status: 0\n")))
        self.sleep.start()
        self.run_perf("start", "--runs", "1", "--settle", "1")
        self.assertEqual([10, 1], self.slept)

    def test_adb_comes_from_the_path_without_an_sdk(self):
        with mock.patch.dict(os.environ, {"ANDROID_HOME": ""}), mock.patch.object(perf.Path, "home", return_value=Path("/nowhere")):
            with mock.patch.object(perf.shutil, "which", return_value="/usr/bin/adb"):
                self.assertEqual("/usr/bin/adb", perf.adb_path())
            with mock.patch.object(perf.shutil, "which", return_value=None), self.assertRaises(SystemExit):
                perf.adb_path()


if __name__ == "__main__":
    unittest.main()
