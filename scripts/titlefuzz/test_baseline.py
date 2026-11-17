import argparse
import gzip
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import baseline


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.work)

    def checkout(self, probes=None):
        checkout = self.work / "checkout"
        (checkout / baseline.TESTS).mkdir(parents=True)
        (checkout / baseline.MAIN).mkdir(parents=True)
        if probes is not None:
            (checkout / baseline.TESTS / "TitleProbes.kt").write_text(probes, encoding="utf-8")
        return checkout

    def test_mains_checkout_gets_the_fuzz_titles_and_mains_probes(self):
        checkout = self.checkout()
        baseline.copy_in(checkout)
        tests, main = checkout / baseline.TESTS, checkout / baseline.MAIN
        for name in ("TitleFuzz.kt", "IcuPattern.kt", "PhoneRegexSets.kt", "LongTitleInputs.kt"):
            self.assertEqual((baseline.ROOT / baseline.TESTS / name).read_bytes(), (tests / name).read_bytes(), name)
        for name in ("PhoneRegex.kt", "PhoneRegexTable.kt"):
            self.assertEqual((baseline.ROOT / baseline.MAIN / name).read_bytes(), (main / name).read_bytes(), name)
        for name in ("WriteTitlesBaseline.kt", "LongTitlesProbe.kt"):
            self.assertEqual((baseline.HERE / name).read_bytes(), (tests / name).read_bytes(), name)
        self.assertEqual((baseline.HERE / "MainTitleProbes.kt").read_bytes(), (tests / "TitleProbes.kt").read_bytes())

    def test_a_checkout_with_probes_of_its_own_keeps_them(self):
        checkout = self.checkout(probes="// its own")
        baseline.copy_in(checkout)
        self.assertEqual("// its own", (checkout / baseline.TESTS / "TitleProbes.kt").read_text(encoding="utf-8"))

    def test_each_timing_asks_the_probe_for_its_own(self):
        def asked(**given):
            return baseline.timing(argparse.Namespace(**{"long": False, "long_all": False, "long_warm": False, "long_instrumental": False, "long_matcher_input": False, **given}))
        self.assertIsNone(asked())
        self.assertEqual("", asked(long=True))
        self.assertEqual("all", asked(long_all=True))
        self.assertEqual("warm", asked(long_warm=True))
        self.assertEqual("instrumental", asked(long_instrumental=True))
        self.assertEqual("matcher-input", asked(long_matcher_input=True))

    def test_a_native_probe_gets_the_pinned_submodule_before_building(self):
        dependency = self.work / "dependency"
        repository = self.work / "repository"
        dependency.mkdir()
        repository.mkdir()

        def git(where, *args):
            return subprocess.run(["git", *args], cwd=where, check=True, capture_output=True, text=True).stdout.strip()

        def commit(where, message):
            git(where, "add", ".")
            git(where, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-qm", message)

        git(dependency, "init", "-b", "main")
        (dependency / "CMakeLists.txt").write_text("pinned visuals", encoding="utf-8")
        commit(dependency, "test(fixture): add the pinned visuals")
        git(repository, "init", "-b", "main")
        git(repository, "-c", "protocol.file.allow=always", "submodule", "add", str(dependency), "third_party/projectm")
        commit(repository, "test(fixture): pin the visuals")
        (dependency / "CMakeLists.txt").write_text("newer visuals", encoding="utf-8")
        commit(dependency, "test(fixture): change the upstream visuals")
        original_git = baseline.git

        def from_repository(*args, **kwargs):
            return original_git(*args, cwd=kwargs.get("cwd", repository))

        def build(checkout, *args, **kwargs):
            self.assertEqual("pinned visuals", (checkout / "third_party/projectm/CMakeLists.txt").read_text(encoding="utf-8"))

        with mock.patch.object(baseline, "git", side_effect=from_repository), \
             mock.patch.object(baseline, "copy_in"), \
             mock.patch.object(baseline, "on_emulator", side_effect=build) as probe, \
             mock.patch.object(baseline.sys, "argv", ["baseline.py", "HEAD", "--long", "--serial", "emulator-fixture"]), \
             mock.patch.dict(os.environ, {"TITLEFUZZ_TMP": str(self.work), "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "protocol.file.allow", "GIT_CONFIG_VALUE_0": "always"}):
            baseline.main()
        probe.assert_called_once()
        self.assertEqual(1, len(git(repository, "worktree", "list", "--porcelain").split("worktree ")) - 1)

    def test_the_device_uses_the_apps_runtime_and_always_removes_both_files(self):
        for failed in (False, True):
            with self.subTest(failed=failed):
                checkout = self.work / str(failed) / "checkout"
                built = checkout / "app/build/intermediates/built_in_kotlinc"
                tests = built / "debugUnitTest/compileDebugUnitTestKotlin/classes/com/astrovm/crosstune"
                main = built / "debug/compileDebugKotlin/classes/com/astrovm/crosstune"
                tests.mkdir(parents=True)
                main.mkdir(parents=True)
                (main / "App.class").write_bytes(b"fixture")
                for name in baseline.TEST_CLASSES:
                    (tests / f"{name}.class").write_bytes(b"fixture")
                ids = checkout / "app/build/intermediates/fixture_r_class_jar/debug/fixture/R.jar"
                app = checkout / "app/build/outputs/apk/debug/app-debug.apk"
                for path in (ids, app):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"fixture")
                sdk = self.work / "sdk"
                (sdk / "build-tools/37.0.0").mkdir(parents=True, exist_ok=True)
                (sdk / "platforms/android-37").mkdir(parents=True, exist_ok=True)
                home = self.work / "home"
                stdlib = home / ".cache/crosstune-gradle-box/seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/fixture/kotlin-stdlib-2.4.21.jar"
                stdlib.parent.mkdir(parents=True, exist_ok=True)
                stdlib.write_bytes(b"fixture")
                failure = subprocess.CalledProcessError(137, ["fixture"]) if failed else None
                with mock.patch.object(baseline, "box") as box, \
                     mock.patch.object(baseline.regextable, "emulator", return_value="emulator-fixture"), \
                     mock.patch.object(baseline.regextable, "SDK", sdk), \
                     mock.patch.object(baseline.regextable, "run") as command, \
                     mock.patch.object(baseline.Path, "home", return_value=home), \
                     mock.patch.object(baseline.subprocess, "run", side_effect=failure) as run:
                    if failed:
                        with self.assertRaises(subprocess.CalledProcessError):
                            baseline.on_emulator(checkout, None, 0, long="all")
                    else:
                        self.assertIsNone(baseline.on_emulator(checkout, None, 0, long="all"))
                self.assertEqual([mock.call(checkout, ":app:assembleDebug"), mock.call(checkout, ":app:compileDebugUnitTestKotlin")], box.call_args_list)
                launched = run.call_args.args[0]
                self.assertIn(f"-cp {baseline.DEVICE_APP}:{baseline.DEVICE_JAR}", launched[-1])
                self.assertIn(mock.call("adb", "-s", "emulator-fixture", "push", str(app), baseline.DEVICE_APP), command.call_args_list)
                self.assertEqual(mock.call("adb", "-s", "emulator-fixture", "shell", f"rm -f {baseline.DEVICE_APP} {baseline.DEVICE_JAR} {baseline.DEVICE_OUT}"), command.call_args_list[-1])
                staged = checkout.parent / "classes/com/astrovm/crosstune"
                self.assertEqual(sorted(f"{name}.class" for name in baseline.TEST_CLASSES), sorted(p.name for p in staged.iterdir()))

    def test_the_readings_are_kept_with_the_commit_and_engine_and_the_same_bytes_each_time(self):
        made = self.work / "made.tsv.gz"
        with gzip.open(made, "wt", encoding="utf-8") as out:
            out.write("ICU 78, API 37\nt1\tr1\nt2\tr2\n")
        first, second = self.work / "a" / "main.tsv.gz", self.work / "b" / "main.tsv.gz"
        self.assertEqual(("ICU 78, API 37", 2), baseline.write_readings(made, first, "abc123"))
        baseline.write_readings(made, second, "abc123")
        with gzip.open(first, "rt", encoding="utf-8") as read:
            self.assertEqual("# abc123 ICU 78, API 37\nt1\tr1\nt2\tr2\n", read.read())
        self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
