"""Runs scripts/record/emulator.sh for real, with a stand-in adb that says what it was asked."""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("emulator.sh")
# Answers as a Play image does: no root, no setprop, no date, and a language kept until it's changed.
FAKE_ADB = r"""#!/bin/sh
shift 2
echo "$*" >> "$FAKE/asked"
case "$*" in
  root*) echo "adbd cannot run as root in production builds"; exit 1 ;;
  *"shell setprop"*) exit 1 ;;
esac
case "$*" in
  *"cmd locale get-device-locale"*) [ -e "$FAKE/old_android" ] && { echo "Unknown option"; exit 255; }; cat "$FAKE/locale" ;;
  *"cmd locale set-device-locale"*) echo "${*##* }" > "$FAKE/locale" ;;
  *"cmd uimode night yes"*) echo "Night mode: yes" > "$FAKE/night" ;;
  *"cmd uimode night no"*) echo "Night mode: no" > "$FAKE/night" ;;
  *"cmd uimode night"*) cat "$FAKE/night" ;;
  *"date +%z"*) cat "$FAKE/zone" ;;
  *"cat /data/local/tmp/record-before"*) cat "$FAKE/before" 2>/dev/null ;;
  *"> /data/local/tmp/record-before"*) printf '%s %s\n' "$(cat "$FAKE/locale")" "$(cut -d' ' -f3 "$FAKE/night")" > "$FAKE/before" ;;
  *"rm -f /data/local/tmp/record-before"*) rm -f "$FAKE/before" ;;
esac
exit 0
"""


class RecordingSetupTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.fake = Path(self.dir.name)
        adb = self.fake / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        (self.fake / "locale").write_text("en-US\n")
        (self.fake / "night").write_text("Night mode: no\n")
        (self.fake / "zone").write_text("+0000\n")

    def run_script(self, *args):
        env = dict(os.environ, PATH=f"{self.fake}:{os.environ['PATH']}", FAKE=str(self.fake), ANDROID_SERIAL="emulator-5580")
        return subprocess.run(["bash", str(SCRIPT), *args], capture_output=True, text=True, env=env, timeout=60)

    def asked(self):
        return (self.fake / "asked").read_text().splitlines()

    def state(self, name):
        return (self.fake / name).read_text().strip()

    def test_setup_needs_no_root(self):
        result = self.run_script("setup", "es-AR", "01:46")
        self.assertEqual(result.returncode, 0, result.stderr)
        for asked in self.asked():
            self.assertFalse(asked.startswith("root"), asked)
            self.assertNotIn("setprop", asked)
            self.assertNotIn("shell date 1", asked)

    def test_setup_sets_the_language_dark_mode_and_the_clock(self):
        self.run_script("setup", "de-DE", "01:46")
        self.assertEqual(self.state("locale"), "de-DE")
        self.assertEqual(self.state("night"), "Night mode: yes")
        set_time = [a for a in self.asked() if "cmd alarm set-time" in a]
        self.assertEqual(len(set_time), 1)
        millis = int(set_time[0].split()[-1])
        self.assertEqual(millis % 86_400_000, (3600 + 46 * 60) * 1000)

    def test_the_clock_is_set_in_the_devices_own_time_zone(self):
        (self.fake / "zone").write_text("-0300\n")
        self.run_script("setup", "es-AR", "01:46")
        millis = int([a for a in self.asked() if "cmd alarm set-time" in a][0].split()[-1])
        self.assertEqual(millis % 86_400_000, (4 * 3600 + 46 * 60) * 1000)

    def test_restore_brings_back_the_language_and_the_mode_it_found(self):
        self.run_script("setup", "es-AR", "01:46")
        result = self.run_script("restore")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state("locale"), "en-US")
        self.assertEqual(self.state("night"), "Night mode: no")

    def test_setup_twice_still_remembers_how_it_was_before_the_first(self):
        self.run_script("setup", "es-AR", "01:46")
        self.run_script("setup", "de-DE", "01:46")
        self.run_script("restore")
        self.assertEqual(self.state("locale"), "en-US")

    def test_restore_leaves_nothing_behind(self):
        self.run_script("setup", "es-AR", "01:46")
        self.run_script("restore")
        self.assertFalse((self.fake / "before").exists())

    def test_restore_without_setup_changes_no_language(self):
        result = self.run_script("restore")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state("locale"), "en-US")
        self.assertFalse([a for a in self.asked() if "set-device-locale" in a])

    def test_an_android_without_the_command_says_so(self):
        (self.fake / "old_android").touch()
        result = self.run_script("setup", "es-AR", "01:46")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("crosstune-phone", result.stderr)

    def test_a_bad_clock_is_turned_down_before_anything_changes(self):
        result = self.run_script("setup", "es-AR", "25:00")
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.fake / "asked").exists())


if __name__ == "__main__":
    unittest.main()
