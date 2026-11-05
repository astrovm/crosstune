"""Runs gradle-box.sh for real, with a stand-in podman that says what it was asked and waits when told."""
import os
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

BOX = Path(__file__).with_name("gradle-box.sh")
ROOT = Path(__file__).resolve().parents[2]
FAKE_PODMAN = """#!/bin/sh
echo "$$ $*" >> "$FAKE_DIR/asked"
if [ -n "$FAKE_DOWNLOADS" ]; then
    for arg in "$@"; do
        case "$arg" in GRADLE_USER_HOME=*)
            home="${arg#GRADLE_USER_HOME=}"
            mkdir -p "$home/caches/modules-2/files-2.1" "$home/wrapper/dists"
            touch "$home/caches/modules-2/files-2.1/some.jar" "$home/caches/modules-2/modules-2.lock" "$home/caches/modules-2/gc.properties" "$home/wrapper/dists/gradle.zip"
        esac
    done
fi
touch "$FAKE_DIR/started.$FAKE_RUN"
while [ -e "$FAKE_DIR/hold.$FAKE_RUN" ]; do sleep 0.05; done
exit "${FAKE_EXIT:-0}"
"""


class GradleBoxTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        (self.dir / "bin").mkdir()
        podman = self.dir / "bin" / "podman"
        podman.write_text(FAKE_PODMAN)
        podman.chmod(podman.stat().st_mode | stat.S_IXUSR)
        self.home = self.dir / "home"
        self.sdk = self.home / "Android" / "Sdk"
        self.sdk.mkdir(parents=True)

    def start(self, run, *args, slots="1", **env):
        return subprocess.Popen(
            [str(BOX), *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env={
                "PATH": f"{self.dir / 'bin'}:{os.environ['PATH']}",
                "HOME": str(self.home),
                "ANDROID_HOME": str(self.sdk),
                "GRADLE_BOX_DIR": str(self.dir / "state"),
                "GRADLE_BOX_SLOTS": slots,
                "FAKE_DIR": str(self.dir),
                "FAKE_RUN": run,
                **env,
            },
        )

    def asked(self):
        path = self.dir / "asked"
        return path.read_text().splitlines() if path.exists() else []

    def wait_for(self, name):
        deadline = time.monotonic() + 20
        while not (self.dir / name).exists():
            self.assertLess(time.monotonic(), deadline, f"{name} never showed up")
            time.sleep(0.02)

    def hold(self, run):
        (self.dir / f"hold.{run}").touch()
        self.addCleanup(lambda: (self.dir / f"hold.{run}").unlink(missing_ok=True))

    def release(self, run):
        (self.dir / f"hold.{run}").unlink()

    def test_runs_gradle_in_the_container_with_only_what_it_needs(self):
        box = self.start("a", ":app:assembleDebug", "--stacktrace")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        command = self.asked()[0]
        self.assertTrue(command.split(" ", 2)[2].startswith("--rm "), command)
        self.assertIn(f"-v {self.sdk}:{self.sdk}:ro", command)
        self.assertIn(f"-v {ROOT}:{ROOT}", command)
        self.assertIn(f"-w {ROOT}", command)
        self.assertIn("--memory-swap", command)
        self.assertTrue(command.endswith("./gradlew --no-daemon :app:assembleDebug --stacktrace"), command)
        mounted = [part.split(":")[0] for part in command.split() if part.startswith("/") and ":" in part]
        self.assertNotIn(str(self.home), mounted)

    def test_the_real_debug_key_goes_in_read_only_when_there_is_one(self):
        key = self.home / ".android" / "debug.keystore"
        box = self.start("a", "help")
        box.communicate(timeout=20)
        self.assertNotIn("debug.keystore", self.asked()[0])
        key.parent.mkdir()
        key.touch()
        box = self.start("a", "help")
        box.communicate(timeout=20)
        self.assertIn(f"-v {key}:/root/.android/debug.keystore:ro", self.asked()[1])

    def test_gives_back_gradles_exit_code(self):
        box = self.start("a", "help", FAKE_EXIT="3")
        box.communicate(timeout=20)
        self.assertEqual(box.returncode, 3)

    def test_says_so_when_there_is_no_sdk(self):
        box = self.start("a", "help", ANDROID_HOME=str(self.dir / "nowhere"))
        output, _ = box.communicate(timeout=20)
        self.assertNotEqual(box.returncode, 0)
        self.assertIn("no Android SDK", output)
        self.assertEqual(self.asked(), [])

    def test_a_second_build_waits_and_says_how_many_are_ahead(self):
        self.hold("first")
        first = self.start("first", "help")
        self.wait_for("started.first")
        second = self.start("second", "help")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.second").exists())
        self.release("first")
        first.communicate(timeout=20)
        output, _ = second.communicate(timeout=20)
        self.assertEqual(second.returncode, 0, output)
        self.assertIn("waiting, 1 ahead of you", output)
        self.assertTrue((self.dir / "started.second").exists())

    def test_builds_start_in_the_order_they_asked(self):
        self.hold("first")
        first = self.start("first", "help")
        self.wait_for("started.first")
        second = self.start("second", "second")
        time.sleep(0.5)
        third = self.start("third", "third")
        time.sleep(0.5)
        self.release("first")
        for box in (first, second, third):
            box.communicate(timeout=20)
        self.assertTrue(self.asked()[1].endswith(" second"), self.asked())
        self.assertTrue(self.asked()[2].endswith(" third"), self.asked())

    def test_two_slots_build_at_once_each_with_its_own_gradle_home(self):
        self.hold("first")
        self.hold("second")
        first = self.start("first", "help", slots="2")
        self.wait_for("started.first")
        second = self.start("second", "help", slots="2")
        self.wait_for("started.second")
        self.release("first")
        self.release("second")
        first.communicate(timeout=20)
        second.communicate(timeout=20)
        homes = {part for command in self.asked() for part in command.split() if part.startswith("GRADLE_USER_HOME=")}
        self.assertEqual(len(homes), 2, self.asked())

    def test_a_build_that_died_holds_nobody_up(self):
        queue = self.dir / "state" / "queue"
        queue.mkdir(parents=True)
        dead = subprocess.Popen(["true"])
        dead.wait()
        (queue / f"1.{dead.pid}").touch()
        box = self.start("a", "help")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("ahead of you", output)
        self.assertEqual(list(queue.iterdir()), [])

    def seed(self):
        return self.dir / "state" / "seed"

    def test_the_first_build_keeps_its_downloads_for_every_other_slot(self):
        box = self.start("a", "help", FAKE_DOWNLOADS="1")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertTrue((self.seed() / "modules-2" / "files-2.1" / "some.jar").exists())
        self.assertTrue((self.seed() / "wrapper" / "dists" / "gradle.zip").exists())
        # Gradle refuses a read-only cache with these in it.
        self.assertFalse((self.seed() / "modules-2" / "modules-2.lock").exists())
        self.assertFalse((self.seed() / "modules-2" / "gc.properties").exists())
        self.assertEqual([path.name for path in (self.dir / "state").glob("seed.*")], [])

    def test_a_failed_build_keeps_nothing(self):
        box = self.start("a", "help", FAKE_DOWNLOADS="1", FAKE_EXIT="1")
        box.communicate(timeout=20)
        self.assertFalse(self.seed().exists())

    def test_a_new_slot_reads_the_kept_downloads_and_downloads_nothing_again(self):
        (self.seed() / "modules-2").mkdir(parents=True)
        (self.seed() / "wrapper").mkdir()
        (self.seed() / "wrapper" / "gradle.zip").touch()
        box = self.start("a", "help")
        box.communicate(timeout=20)
        command = self.asked()[0]
        self.assertIn(f"-v {self.seed()}:{self.seed()}:ro", command)
        self.assertIn(f"GRADLE_RO_DEP_CACHE={self.seed()}", command)
        self.assertTrue((self.dir / "state" / "gradle-1" / "wrapper" / "gradle.zip").exists())

    def test_kept_downloads_stay_as_they_are_once_there(self):
        (self.seed() / "modules-2").mkdir(parents=True)
        box = self.start("a", "help", FAKE_DOWNLOADS="1")
        box.communicate(timeout=20)
        self.assertEqual(list((self.seed() / "modules-2").iterdir()), [])

    def test_without_kept_downloads_there_is_nothing_to_read(self):
        box = self.start("a", "help")
        box.communicate(timeout=20)
        self.assertNotIn("GRADLE_RO_DEP_CACHE", self.asked()[0])

    def test_cleanup_removes_oldest_idle_home_only_when_over_limit(self):
        state = self.dir / "state"
        for slot, age in ((2, 100), (3, 200)):
            home = state / f"gradle-{slot}"
            home.mkdir(parents=True)
            (home / "data").write_bytes(b"x" * 8192)
            os.utime(home, (age, age))
        (self.seed() / "modules-2").mkdir(parents=True)
        box = self.start("a", "help", GRADLE_BOX_CACHE_BYTES="12000")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertFalse((state / "gradle-2").exists())
        self.assertTrue((state / "gradle-3" / "data").exists())
        self.assertTrue(self.seed().exists())
        self.assertIn("gradle-2", output)

    def test_cleanup_leaves_cache_under_limit(self):
        home = self.dir / "state" / "gradle-2"
        home.mkdir(parents=True)
        (home / "data").write_bytes(b"x" * 8192)
        box = self.start("a", "help", GRADLE_BOX_CACHE_BYTES="1000000")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertTrue((home / "data").exists())
        self.assertNotIn("deleted", output)

    def test_cleanup_never_removes_a_running_slots_home(self):
        self.hold("first")
        first = self.start("first", "help", slots="2")
        self.wait_for("started.first")
        home = self.dir / "state" / "gradle-1"
        (home / "data").write_bytes(b"x" * 8192)
        second = self.start("second", "help", slots="2", GRADLE_BOX_CACHE_BYTES="1")
        output, _ = second.communicate(timeout=20)
        self.assertEqual(second.returncode, 0, output)
        self.assertTrue((home / "data").exists())
        self.release("first")
        first.communicate(timeout=20)

    def test_stopping_it_stops_the_build(self):
        self.hold("first")
        first = self.start("first", "help")
        self.wait_for("started.first")
        pid = int(self.asked()[0].split(" ", 1)[0])
        first.terminate()
        first.communicate(timeout=20)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)


if __name__ == "__main__":
    unittest.main()
