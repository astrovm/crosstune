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
import perf

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
  "install -r -d "*) cat "$4" >> "$FAKE/installed-packages"; echo Success ;;
  "shell wm size") printf 'Physical size: 1080x2316\nOverride size: 720x1544\n' ;;
  "shell dumpsys thermalservice") cat "$FAKE/thermal" ;;
  "shell dumpsys battery") cat "$FAKE/battery" ;;
  "shell dumpsys package com.astrovm.crosstune.dev") printf 'pkgFlags=[ %s HAS_CODE ]\nx\n' "$(cat "$FAKE/flags")" ;;
  "shell dumpsys gfxinfo com.astrovm.crosstune.dev") cat "$FAKE/gfxinfo" ;;
  "shell am start -W "*) cat "$FAKE/start" ;;
  "shell pidof com.astrovm.crosstune.dev") echo 123 ;;
  "shell cat /proc/123/stat /proc/123/task/123/stat")
    n=$(cat "$FAKE/cats"); echo $((n + 1)) > "$FAKE/cats"
    if [ "$n" = 0 ]; then printf '%s\n%s\n' "$STAT_APP_0" "$STAT_MAIN_0"; else printf '%s\n%s\n' "$STAT_APP_1" "$STAT_MAIN_1"; fi ;;
esac
"""

FAKE_AAPT2 = r"""#!/bin/sh
case "$*" in
  "dump packagename "*production.apk) echo com.astrovm.crosstune ;;
  "dump packagename "*unknown.apk) exit 1 ;;
  "dump packagename "*) sed -n 1p "$3" ;;
  "dump badging "*)
    code=$(sed -n 2p "$3"); echo "package: name='$(sed -n 1p "$3")' versionCode='${code:-1}'"
    if grep -qx debuggable "$3"; then echo application-debuggable; fi ;;
  *) exit 2 ;;
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
        aapt2 = home / "build-tools/37.0.0/aapt2"
        aapt2.parent.mkdir(parents=True)
        aapt2.write_text(FAKE_AAPT2)
        aapt2.chmod(aapt2.stat().st_mode | stat.S_IEXEC)
        (home / "gfxinfo").write_text(GFXINFO)
        (home / "thermal").write_text("Thermal Status: 0\n")
        (home / "battery").write_text("Current Battery Service state:\n  temperature: 312\n")
        (home / "start").write_text("Status: ok\nTotalTime: 812\n")
        (home / "flags").write_text("")
        (home / "cats").write_text("0")
        for name in ("a.apk", "b.apk", "missing.apk.fail"):
            (home / name).write_text("com.astrovm.crosstune.dev\n")
        (home / "production.apk").write_text("com.astrovm.crosstune\n")
        (home / "unknown.apk").touch()
        self.home_path = home
        self.env = mock.patch.dict(os.environ, {
            "ANDROID_HOME": str(home), "ADB": "", "FAKE": str(home),
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
        args = list(argv)
        for index, option in enumerate(args[:-1]):
            if option == "--build":
                label, name = args[index + 1].split("=", 1)
                args[index + 1] = f"{label}={self.home_path / name}"
        out = io.StringIO()
        with redirect_stdout(out):
            perf.main(args)
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def asked(self):
        log = self.home_path / "asked"
        return log.read_text().splitlines() if log.exists() else []

    def test_aapt2_comes_from_android_studios_sdk_when_the_named_one_has_none(self):
        studio = self.home_path / "studio"
        aapt2 = studio / "Android/Sdk/build-tools/36.0.0/aapt2"
        aapt2.parent.mkdir(parents=True)
        aapt2.touch()
        # Both names an SDK can go by, as CI's runner sets ANDROID_SDK_ROOT to an SDK that has its own aapt2.
        named = {"ANDROID_HOME": str(self.home_path / "empty"), "ANDROID_SDK_ROOT": str(self.home_path / "empty")}
        with mock.patch.dict(os.environ, {**named, "HOME": str(studio)}):
            self.assertEqual(str(aapt2), perf.aapt2_path())

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

    def test_gfxinfo_without_numbers_says_none(self):
        run = perf.gfxinfo("No process found\n")
        self.assertEqual((0, 0, 0.0, 0), (run["frames"], run["janky"], run["janky_pct"], run["p90_ms"]))

    def test_jank_on_a_screen_that_did_not_scroll_stops_rather_than_read_it_as_all_janky(self):
        (self.home_path / "gfxinfo").write_text(GFXINFO.replace("Total frames rendered: 1000", "Total frames rendered: 15"))
        with self.assertRaisesRegex(SystemExit, "Only 15 frames were drawn over 16 flings"):
            self.run_perf("jank", "--runs", "1")

    def test_jank_with_a_frame_for_each_fling_counts(self):
        (self.home_path / "gfxinfo").write_text(GFXINFO.replace("Total frames rendered: 1000", "Total frames rendered: 16"))
        self.assertEqual(16, self.run_perf("jank", "--runs", "1")[0]["frames"])

    def test_builds_take_turns_and_one_not_debuggable_is_compiled_from_its_profile(self):
        lines = self.run_perf("start", "--runs", "2", "--build", "old=a.apk", "--build", "new=b.apk")
        self.assertEqual(["old", "new", "old", "new"], [line["label"] for line in lines[:4]])
        self.assertEqual(812, lines[0]["total_ms"])
        self.assertEqual([{"label": "old", "runs": 2, "total_ms": {"mean": 812, "sd": 0.0, "min": 812, "max": 812}},
                          {"label": "new", "runs": 2, "total_ms": {"mean": 812, "sd": 0.0, "min": 812, "max": 812}}], lines[4:])
        asked = self.asked()
        self.assertEqual(["a.apk", "b.apk"] * 2, [Path(line.removeprefix("install -r -d ")).name for line in asked if line.startswith("install")])
        self.assertEqual(4, asked.count("shell cmd package compile -f -m speed-profile com.astrovm.crosstune.dev"))

    def test_a_debuggable_build_runs_as_it_is(self):
        (self.home_path / "flags").write_text("DEBUGGABLE")
        self.run_perf("start", "--runs", "1", "--build", "dev=a.apk")
        self.assertFalse([line for line in self.asked() if "compile" in line or "broadcast" in line])

    def test_a_failed_install_stops(self):
        with self.assertRaises(SystemExit) as stopped:
            self.run_perf("start", "--runs", "1", "--build", "x=missing.apk.fail")
        self.assertIn("missing.apk.fail", str(stopped.exception))

    def test_only_dev_builds_are_allowed_before_any_install(self):
        for apk in ("production.apk", "unknown.apk"):
            with self.subTest(apk=apk), self.assertRaisesRegex(SystemExit, apk):
                self.run_perf("start", "--runs", "1", "--build", "dev=a.apk", "--build", f"other={apk}")
        self.assertEqual([], self.asked())

    def test_release_like_builds_of_two_version_codes_stop_before_any_install(self):
        (self.home_path / "old.apk").write_text("com.astrovm.crosstune.dev\n2270100\n")
        (self.home_path / "new.apk").write_text("com.astrovm.crosstune.dev\n2280000\n")
        with self.assertRaisesRegex(SystemExit, "old 2270100, new 2280000"):
            self.run_perf("start", "--runs", "1", "--build", "old=old.apk", "--build", "new=new.apk")
        self.assertEqual([], self.asked())

    def test_a_newer_build_that_is_not_debuggable_stops_an_older_debuggable_one(self):
        (self.home_path / "old.apk").write_text("com.astrovm.crosstune.dev\n2270100\ndebuggable\n")
        (self.home_path / "new.apk").write_text("com.astrovm.crosstune.dev\n2280000\n")
        with self.assertRaisesRegex(SystemExit, "Build them with the same versionCode"):
            self.run_perf("start", "--runs", "1", "--build", "old=old.apk", "--build", "new=new.apk")
        self.assertEqual([], self.asked())

    def test_debuggable_builds_of_two_version_codes_take_turns(self):
        (self.home_path / "old.apk").write_text("com.astrovm.crosstune.dev\n2270100\ndebuggable\n")
        (self.home_path / "new.apk").write_text("com.astrovm.crosstune.dev\n2280000\ndebuggable\n")
        lines = self.run_perf("start", "--runs", "1", "--build", "old=old.apk", "--build", "new=new.apk")
        self.assertEqual(["old", "new"], [line["label"] for line in lines[:2]])

    def test_each_install_reads_the_validated_copy_after_the_source_changes(self):
        apk = self.home_path / "a.apk"
        self.sleep.stop()

        def replace_during_run(seconds):
            self.slept.append(seconds)
            apk.write_text("com.astrovm.crosstune\n")

        self.sleep = mock.patch.object(perf.time, "sleep", replace_during_run)
        self.sleep.start()
        self.run_perf("start", "--runs", "2", "--build", "dev=a.apk")
        self.assertEqual(
            ["com.astrovm.crosstune.dev"] * 2,
            (self.home_path / "installed-packages").read_text().splitlines(),
        )
        installs = [Path(line.removeprefix("install -r -d ")) for line in self.asked() if line.startswith("install")]
        self.assertFalse(any(path.exists() for path in installs))

    def test_a_start_without_a_complete_total_time_line_stops_without_report(self):
        for output in ("Status: ok\n", "NotTotalTime: 812\n", "TotalTime: 812ms\n"):
            (self.home_path / "start").write_text(output)
            report = io.StringIO()
            with self.subTest(output=output), redirect_stdout(report), self.assertRaisesRegex(SystemExit, "TotalTime"):
                perf.main(["start", "--runs", "1"])
            self.assertEqual("", report.getvalue())

    def test_a_subzero_battery_is_cool(self):
        battery = self.home_path / "battery"
        battery.write_text("  temperature: -10\n")
        self.sleep.stop()

        def guard(seconds):
            self.slept.append(seconds)
            if seconds == 10:
                self.fail("A subzero battery triggered a cooling wait")

        self.sleep = mock.patch.object(perf.time, "sleep", guard)
        self.sleep.start()
        self.assertEqual(-1.0, self.run_perf("start", "--runs", "1")[0]["battery_c"])

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

    def test_it_waits_for_a_hot_battery_and_explains_why(self):
        battery = self.home_path / "battery"
        battery.write_text("  temperature: 400\n")
        self.sleep.stop()
        self.sleep = mock.patch.object(perf.time, "sleep", lambda seconds: (self.slept.append(seconds), battery.write_text("  temperature: 399\n")))
        self.sleep.start()
        log = io.StringIO()
        with mock.patch.object(sys, "stderr", log):
            self.run_perf("start", "--runs", "1", "--settle", "1")
        self.assertEqual([10, 1], self.slept)
        self.assertIn("battery 40 C (below 40 C)", log.getvalue())

    def test_it_waits_while_the_phone_says_no_status(self):
        thermal = self.home_path / "thermal"
        thermal.write_text("nothing\n")
        self.sleep.stop()
        self.sleep = mock.patch.object(perf.time, "sleep", lambda seconds: (self.slept.append(seconds), thermal.write_text("Thermal Status: 0\n")))
        self.sleep.start()
        log = io.StringIO()
        with mock.patch.object(sys, "stderr", log):
            self.run_perf("start", "--runs", "1", "--settle", "1")
        self.assertEqual([10, 1], self.slept)
        self.assertIn("no thermal status", log.getvalue())

    def test_a_missing_battery_temperature_is_not_treated_as_cool(self):
        battery = self.home_path / "battery"
        battery.write_text("Current Battery Service state:\n")
        self.sleep.stop()
        self.sleep = mock.patch.object(perf.time, "sleep", lambda seconds: (self.slept.append(seconds), battery.write_text("  temperature: 312\n")))
        self.sleep.start()
        log = io.StringIO()
        with mock.patch.object(sys, "stderr", log):
            self.run_perf("start", "--runs", "1", "--settle", "1")
        self.assertEqual([10, 1], self.slept)
        self.assertIn("no battery temperature", log.getvalue())


if __name__ == "__main__":
    unittest.main()
