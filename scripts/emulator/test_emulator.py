"""Runs emulator.py for real, with stand-ins for the Android SDK's tools that say what they were asked."""
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent
SCRIPT = HERE / "emulator.py"
ROOT = HERE.parents[1]
DECLARED = json.loads((HERE / "emulators.json").read_text())
PHONE, TV = "crosstune-phone", "crosstune-tv"
PHONE_IMAGE_DIR = "system-images/android-37.0/google_apis_playstore/x86_64"

ASK = 'echo "$(basename "$0") $*" >> "$FAKE/asked"\n'
FAKE_TOOLS = {
    "cmdline-tools/latest/bin/sdkmanager": ASK + 'mkdir -p "$ANDROID_HOME/$(echo "$2" | tr ";" "/")"\n',
    "cmdline-tools/latest/bin/avdmanager": ASK + r"""
cat > /dev/null
name=$(echo "$*" | sed 's/.*-n \([^ ]*\).*/\1/')
image=$(echo "$*" | sed 's/.*-k \([^ ]*\).*/\1/' | tr ';' '/')
mkdir -p "$ANDROID_AVD_HOME/$name.avd"
printf 'image.sysdir.1=%s/\nhw.ramSize=96\n' "$image" > "$ANDROID_AVD_HOME/$name.avd/config.ini"
echo "path=$ANDROID_AVD_HOME/$name.avd" > "$ANDROID_AVD_HOME/$name.ini"
""",
    "emulator/emulator": ASK + 'touch "$FAKE/booted.$4"\n',
    "platform-tools/adb": ASK + r"""
case "$*" in
  devices) echo "List of devices attached"; for f in "$FAKE"/booted.*; do [ -e "$f" ] && printf 'emulator-%s\tdevice\n' "${f##*.}"; done ;;
  *"getprop sys.boot_completed"*) echo 1 ;;
  *"pm path "*) pkg="${*##* }"; grep -qx "$pkg" "$FAKE/installed" 2>/dev/null && echo "package:/data/app/$pkg/base.apk" ;;
  *"dumpsys account"*) cat "$FAKE/accounts" 2>/dev/null ;;
  *"am start"*) pkg="${*##*id=}"; [ -e "$FAKE/installs_when_opened" ] && echo "$pkg" >> "$FAKE/installed" ;;
esac
exit 0
""",
}


class EmulatorScriptTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        base = Path(self.dir.name)
        self.sdk, self.avds, self.fake = base / "sdk", base / "avd", base / "fake"
        self.avds.mkdir()
        self.fake.mkdir()
        for path, body in FAKE_TOOLS.items():
            tool = self.sdk / path
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text("#!/bin/sh\n" + body)
            tool.chmod(tool.stat().st_mode | stat.S_IEXEC)

    def run_script(self, *args, sdk=None):
        env = {k: v for k, v in os.environ.items() if not k.startswith("ANDROID_")}
        env.update(
            ANDROID_HOME=str(sdk or self.sdk), ANDROID_AVD_HOME=str(self.avds), FAKE=str(self.fake),
            EMULATOR_ABI="x86_64", EMULATOR_APP_WAIT="0",
        )
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env, timeout=60)

    def asked(self):
        path = self.fake / "asked"
        return path.read_text().splitlines() if path.exists() else []

    def boot(self, name=PHONE):
        (self.fake / f"booted.{DECLARED[name]['port']}").touch()

    def install(self, *packages):
        (self.fake / "installed").write_text("".join(p + "\n" for p in packages))

    def everything_on_the_phone(self):
        self.install(*DECLARED[PHONE]["play_apps"].values())
        (self.fake / "accounts").write_text("Account {name=someone@example.invalid, type=com.google}\n")

    # create

    def test_create_makes_each_declared_emulator_from_its_image(self):
        result = self.run_script("create")
        self.assertEqual(result.returncode, 0, result.stderr)
        asked = self.asked()
        self.assertIn("sdkmanager --install system-images;android-37.0;google_apis_playstore;x86_64", asked)
        self.assertIn("sdkmanager --install system-images;android-36;google-tv;x86_64", asked)
        self.assertTrue(any(a.startswith("avdmanager create avd") and f"-n {PHONE}" in a and "-d pixel_8" in a for a in asked), asked)
        self.assertTrue(any(a.startswith("avdmanager create avd") and f"-n {TV}" in a and "-d tv_1080p" in a for a in asked), asked)

    def test_create_writes_the_declared_settings_over_the_defaults(self):
        self.run_script("create", PHONE)
        config = (self.avds / f"{PHONE}.avd" / "config.ini").read_text()
        self.assertIn("hw.ramSize=4096\n", config)
        self.assertIn("hw.cpu.ncore=6\n", config)
        self.assertNotIn("hw.ramSize=96", config)

    def test_create_twice_keeps_the_emulator_and_its_logins(self):
        self.run_script("create", PHONE)
        marker = self.avds / f"{PHONE}.avd" / "userdata-qemu.img"
        marker.write_text("logged in")
        result = self.run_script("create", PHONE)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(a.startswith("avdmanager") for a in self.asked()), 1)
        self.assertEqual(marker.read_text(), "logged in")

    def test_create_wont_overwrite_an_emulator_on_another_image(self):
        self.run_script("create", PHONE)
        config = self.avds / f"{PHONE}.avd" / "config.ini"
        config.write_text("image.sysdir.1=system-images/android-30/google_apis/x86_64/\n")
        result = self.run_script("create", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("android-30", result.stdout + result.stderr)
        self.assertIn("--replace", result.stdout + result.stderr)
        self.assertEqual(sum(a.startswith("avdmanager") for a in self.asked()), 1)

    def test_replace_makes_it_again_on_the_declared_image(self):
        self.run_script("create", PHONE)
        config = self.avds / f"{PHONE}.avd" / "config.ini"
        config.write_text("image.sysdir.1=system-images/android-30/google_apis/x86_64/\n")
        result = self.run_script("create", PHONE, "--replace")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(PHONE_IMAGE_DIR, config.read_text())

    def test_an_unknown_name_says_which_there_are(self):
        result = self.run_script("create", "crosstune-tablet")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(PHONE, result.stdout + result.stderr)

    # start

    def test_start_uses_the_declared_port_and_hardware_graphics(self):
        self.run_script("create", PHONE)
        result = self.run_script("start", PHONE)
        self.assertEqual(result.returncode, 0, result.stderr)
        started = [a for a in self.asked() if a.startswith("emulator ")]
        self.assertEqual(len(started), 1, started)
        self.assertIn(f"-avd {PHONE} -port 5580", started[0])
        self.assertIn("-gpu host", started[0])
        self.assertIn("emulator-5580", result.stdout)

    def test_start_hides_the_window_unless_asked_for_it(self):
        self.run_script("create", PHONE)
        self.run_script("start", PHONE)
        started = [a for a in self.asked() if a.startswith("emulator ")]
        self.assertRegex(started[0], "-qt-hide-window")
        (self.fake / "booted.5580").unlink()
        (self.fake / "asked").unlink()
        self.run_script("start", PHONE, "--window")
        started = [a for a in self.asked() if a.startswith("emulator ")]
        self.assertNotRegex(started[0], "-qt-hide-window")

    def test_start_when_it_runs_already_doesnt_start_another(self):
        self.run_script("create", PHONE)
        self.boot()
        result = self.run_script("start", PHONE)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse([a for a in self.asked() if a.startswith("emulator ")])
        self.assertIn("emulator-5580", result.stdout)

    def test_start_before_create_says_so(self):
        result = self.run_script("start", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("create", result.stdout + result.stderr)
        self.assertFalse([a for a in self.asked() if a.startswith("emulator ")])

    # apps

    def test_apps_opens_the_play_page_of_only_whats_missing(self):
        self.run_script("create", PHONE)
        self.boot()
        (self.fake / "accounts").write_text("Account {name=someone@example.invalid, type=com.google}\n")
        self.install("com.spotify.music")
        (self.fake / "installs_when_opened").touch()
        result = self.run_script("apps", PHONE)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        opened = [a for a in self.asked() if "am start" in a]
        self.assertIn("market://details?id=com.qobuz.music", " ".join(opened))
        self.assertNotIn("com.spotify.music", " ".join(opened))
        self.assertEqual(len(opened), len(DECLARED[PHONE]["play_apps"]) - 1)

    def test_apps_fails_while_something_is_still_missing(self):
        self.run_script("create", PHONE)
        self.boot()
        (self.fake / "accounts").write_text("Account {name=someone@example.invalid, type=com.google}\n")
        result = self.run_script("apps", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Qobuz", result.stdout)

    def test_apps_asks_for_the_sign_in_first(self):
        self.run_script("create", PHONE)
        self.boot()
        result = self.run_script("apps", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Sign in", result.stdout + result.stderr)
        self.assertFalse([a for a in self.asked() if "am start" in a])

    def test_apps_needs_it_running(self):
        self.run_script("create", PHONE)
        result = self.run_script("apps", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("start", result.stdout + result.stderr)

    # check

    def test_check_says_whats_missing_when_nothing_is_there(self):
        result = self.run_script("check", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("create", result.stdout)

    def test_check_passes_with_everything_in_place(self):
        self.run_script("create", PHONE)
        self.boot()
        self.everything_on_the_phone()
        result = self.run_script("check", PHONE)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_check_names_a_missing_app_and_a_missing_sign_in(self):
        self.run_script("create", PHONE)
        self.boot()
        self.install("com.spotify.music")
        result = self.run_script("check", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Qobuz", result.stdout)
        self.assertIn("Sign in", result.stdout)

    def test_check_never_prints_the_account(self):
        self.run_script("create", PHONE)
        self.boot()
        self.everything_on_the_phone()
        result = self.run_script("check", PHONE)
        self.assertNotIn("someone@example.invalid", result.stdout + result.stderr)

    def test_check_lists_what_only_a_person_can_confirm_without_failing(self):
        self.run_script("create", PHONE)
        self.boot()
        self.everything_on_the_phone()
        result = self.run_script("check", PHONE)
        self.assertIn("Log in to Qobuz", result.stdout)
        self.assertEqual(result.returncode, 0)

    def test_check_doesnt_fail_on_an_optional_app(self):
        self.run_script("create", PHONE)
        self.boot()
        self.everything_on_the_phone()
        result = self.run_script("check", PHONE)
        self.assertIn("Morphe", result.stdout)
        self.assertEqual(result.returncode, 0)

    def test_check_without_it_running_says_the_apps_werent_looked_at(self):
        self.run_script("create", PHONE)
        result = self.run_script("check", PHONE)
        self.assertIn("start", result.stdout)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_check_points_out_an_emulator_nobody_declared(self):
        (self.avds / "old-one.ini").write_text("path=somewhere\n")
        result = self.run_script("check")
        self.assertIn("old-one", result.stdout)

    def test_check_flags_an_emulator_on_another_image(self):
        self.run_script("create", PHONE)
        (self.avds / f"{PHONE}.avd" / "config.ini").write_text("image.sysdir.1=system-images/android-30/google_apis/x86_64/\n")
        result = self.run_script("check", PHONE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("android-30", result.stdout)

    # A machine without the tools

    def test_no_sdk_says_so_instead_of_failing_with_a_traceback(self):
        result = self.run_script("check", sdk=Path(self.dir.name) / "nothing-here")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Android SDK", result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)


class DeclarationTest(unittest.TestCase):
    def test_every_app_the_phone_can_open_songs_in_is_on_the_emulator(self):
        source = (ROOT / "app/src/main/java/com/astrovm/crosstune/MusicService.kt").read_text()
        packages = re.findall(r'R\.drawable\.ic_service_\w+, "([\w.]+)", (?:true|false),', source)
        self.assertGreater(len(packages), 5, "MusicService.kt changed shape, so this finds nothing to check")
        declared = DECLARED[PHONE]["play_apps"].values()
        self.assertEqual([p for p in packages if p not in declared], [])

    def test_every_phone_emulator_has_the_play_store(self):
        for name, emulator in DECLARED.items():
            if "tv" not in name:
                self.assertIn("playstore", emulator["image"], name)

    def test_no_two_emulators_share_a_port(self):
        ports = [e["port"] for e in DECLARED.values()]
        self.assertEqual(len(ports), len(set(ports)))

    def test_ports_are_the_even_ones_the_emulator_accepts(self):
        for name, emulator in DECLARED.items():
            self.assertTrue(5554 <= emulator["port"] <= 5682 and emulator["port"] % 2 == 0, name)

    def test_every_image_works_on_any_machine(self):
        for name, emulator in DECLARED.items():
            self.assertIn("{abi}", emulator["image"], name)

    def test_this_public_repo_names_no_account(self):
        self.assertNotIn("@", (HERE / "emulators.json").read_text())


if __name__ == "__main__":
    unittest.main()
