"""Runs gradle-box.sh for real, with a stand-in podman that says what it was asked and waits when told."""
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

BOX = Path(__file__).with_name("gradle-box.sh")
ROOT = Path(__file__).resolve().parents[2]
FAKE_PODMAN = """#!/bin/sh
if [ "$1" = "info" ]; then
    echo "$*" >> "$FAKE_DIR/info"
    if [ -z "$FAKE_MACHINE" ]; then
        echo "Cannot connect to Podman" >&2
        exit 125
    fi
    echo "$FAKE_MACHINE"
    exit 0
fi
if [ "$1" = "image" ]; then
    if [ -n "$FAKE_NO_IMAGE" ]; then
        exit 1
    fi
    exit 0
fi
if [ "$1" = "build" ]; then
    echo "$$ $*" >> "$FAKE_DIR/asked"
    exit "${FAKE_BUILD_EXIT:-0}"
fi
echo "$$ $*" >> "$FAKE_DIR/asked"
if [ -n "$FAKE_DOWNLOADS" ]; then
    for arg in "$@"; do
        case "$arg" in GRADLE_USER_HOME=*)
            home="${arg#GRADLE_USER_HOME=}"
            mkdir -p "$home/caches/modules-2/files-2.1" "$home/wrapper/dists"
            printf data > "$home/caches/modules-2/files-2.1/some.jar"
            touch "$home/caches/modules-2/modules-2.lock" "$home/caches/modules-2/gc.properties" "$home/wrapper/dists/gradle.zip"
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
        self.pressure = self.dir / "pressure"
        self.meminfo = self.dir / "meminfo"
        self.meminfo.write_text("MemTotal:       67108864 kB\nMemFree:         1000000 kB\n")
        self.stall(lately=0, total_s=0)

    def start(self, run, *args, slots="1", **env):
        return subprocess.Popen(
            [str(BOX), *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env={name: value for name, value in {
                "PATH": f"{self.dir / 'bin'}:{os.environ['PATH']}",
                "HOME": str(self.home),
                "ANDROID_HOME": str(self.sdk),
                "GRADLE_BOX_DIR": str(self.dir / "state"),
                "GRADLE_BOX_SLOTS": slots,
                # 1 GiB a build, so the slots asked for fit on any machine the tests run on.
                "GRADLE_BOX_MEMORY": "1",
                # The machine running the tests may be short of memory; these builds don't care.
                "GRADLE_BOX_PRESSURE": str(self.pressure),
                # The machine's memory, as Linux tells it, on a Mac too: 64 GiB.
                "GRADLE_BOX_MEMINFO": str(self.meminfo),
                "FAKE_DIR": str(self.dir),
                "FAKE_RUN": run,
                **env,
            }.items() if value is not None},
        )

    def tool(self, name, script):
        """A stand-in for one of the machine's tools, ahead of the real one."""
        path = self.dir / "bin" / name
        path.write_text("#!/bin/sh\n" + script)
        path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def only_a_macs_tools(self):
        """cp, du and mv that take no GNU option, and no flock at all, as on a Mac."""
        for name, option in (("du", "-sB1"), ("mv", "-T")):
            real = shutil.which(name)
            self.tool(name, f'for arg in "$@"; do case "$arg" in {option}) echo "{name}: illegal option" >&2; exit 64;; esac; done\nexec {real} "$@"\n')
        # A Mac's cp shares blocks with -c, which GNU's doesn't know, so it is taken off on the way.
        self.tool(
            "cp",
            'for arg in "$@"; do case "$arg" in --*) echo "cp: illegal option" >&2; exit 64;; esac; done\n'
            'for arg in "$@"; do shift; [ "$arg" = -c ] && touch "$FAKE_DIR/cp-shared" || set -- "$@" "$arg"; done\n'
            f'exec {shutil.which("cp")} "$@"\n',
        )
        self.tool("flock", f'touch "{self.dir}/flock-asked"\necho "flock: command not found" >&2\nexit 127\n')

    def stall(self, lately, total_s):
        """Linux's memory pressure as /proc/pressure/memory says it: how much every task waited on memory."""
        self.pressure.write_text(
            f"some avg10={lately + 1}.00 avg60=0.00 avg300=0.00 total={total_s * 2_000_000}\n"
            f"full avg10={lately}.25 avg60=0.00 avg300=0.00 total={total_s * 1_000_000}\n"
        )

    def image(self):
        digest = hashlib.sha256((BOX.parent / "Containerfile").read_bytes()).hexdigest()
        return f"localhost/crosstune-gradle-box:{digest}"

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

    def test_a_chosen_replay_folder_is_mounted_and_named_in_the_box(self):
        kept = self.dir / "replay-cache"
        kept.mkdir()
        box = self.start("replay", ":app:testDebugUnitTest", SYNCREPLAY_DIR=str(kept))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertIn(f"-v {kept}:{kept}", self.asked()[0])
        self.assertIn(f"-e SYNCREPLAY_DIR={kept}", self.asked()[0])

    def test_no_replay_folder_is_mounted_unless_chosen(self):
        box = self.start("plain", ":app:assembleDebug")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("SYNCREPLAY_DIR", self.asked()[0])

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
        self.assertIn(self.image(), command)
        self.assertNotIn("/usr/bin/git", command)
        self.assertTrue(command.endswith("./gradlew --no-daemon :app:assembleDebug --stacktrace"), command)
        mounted = [part.split(":")[0] for part in command.split() if part.startswith("/") and ":" in part]
        self.assertNotIn(str(self.home), mounted)
        self.assertEqual([line for line in self.asked() if " build " in f" {line} "], [])

    def test_builds_the_image_when_it_is_missing(self):
        box = self.start("a", "help", FAKE_NO_IMAGE="1")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        build, run = self.asked()
        self.assertIn("build", build.split())
        self.assertIn(self.image(), build)
        self.assertIn(str(BOX.parent / "Containerfile"), build)
        self.assertIn(self.image(), run)
        self.assertNotIn("/usr/bin/git", run)
        self.assertIn("image built in", output)

    def test_a_failed_image_build_does_not_start_gradle(self):
        box = self.start("a", "help", FAKE_NO_IMAGE="1", FAKE_BUILD_EXIT="1")
        output, _ = box.communicate(timeout=20)
        self.assertNotEqual(box.returncode, 0, output)
        self.assertEqual(len(self.asked()), 1)
        self.assertIn("build", self.asked()[0].split())
        self.assertNotIn("gradlew", output)

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

    def test_asking_for_more_slots_than_fit_in_memory_still_waits(self):
        self.hold("first")
        first = self.start("first", "help", slots="2", GRADLE_BOX_MEMORY="100000")
        self.wait_for("started.first")
        second = self.start("second", "help", slots="2", GRADLE_BOX_MEMORY="100000")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.second").exists())
        self.release("first")
        first.communicate(timeout=20)
        output, _ = second.communicate(timeout=20)
        self.assertIn("waiting, 1 ahead of you", output)
        self.assertTrue((self.dir / "started.second").exists())

    def test_builds_run_side_by_side_while_what_they_ask_for_fits(self):
        # 64 GiB of RAM leaves 48 for builds: two that ask for 16 fit, and a third that asks for 32 doesn't.
        for run in ("first", "second", "third"):
            self.hold(run)
        first = self.start("first", "help", slots=None, GRADLE_BOX_MEMORY="16")
        self.wait_for("started.first")
        second = self.start("second", "help", slots=None, GRADLE_BOX_MEMORY="16")
        self.wait_for("started.second")
        third = self.start("third", "help", slots=None, GRADLE_BOX_MEMORY="32")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.third").exists())
        self.release("first")
        first.communicate(timeout=20)
        self.wait_for("started.third")
        self.release("second")
        self.release("third")
        second.communicate(timeout=20)
        output, _ = third.communicate(timeout=20)
        self.assertIn("waiting, 2 ahead of you", output)

    def test_a_small_build_waits_behind_a_big_one_that_asked_first(self):
        for run in ("first", "big", "small"):
            self.hold(run)
        first = self.start("first", "help", slots=None, GRADLE_BOX_MEMORY="32")
        self.wait_for("started.first")
        big = self.start("big", "help", slots=None, GRADLE_BOX_MEMORY="32")
        time.sleep(0.3)
        small = self.start("small", "help", slots=None, GRADLE_BOX_MEMORY="8")
        time.sleep(0.5)
        # It would fit beside the first, and then the big one would never get its turn.
        self.assertFalse((self.dir / "started.small").exists())
        self.assertFalse((self.dir / "started.big").exists())
        self.release("first")
        first.communicate(timeout=20)
        self.wait_for("started.big")
        self.wait_for("started.small")
        self.release("big")
        self.release("small")
        big.communicate(timeout=20)
        small.communicate(timeout=20)

    def test_a_build_asking_for_more_than_the_machine_has_runs_alone(self):
        box = self.start("a", "help", slots=None, GRADLE_BOX_MEMORY="100000")
        box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0)
        self.assertTrue((self.dir / "started.a").exists())

    def test_a_build_gets_the_share_of_the_cpus_that_its_memory_is(self):
        cores = os.cpu_count()
        half = self.start("half", "help", slots=None, GRADLE_BOX_MEMORY="24")
        half.communicate(timeout=20)
        whole = self.start("whole", "help", slots=None, GRADLE_BOX_MEMORY="48")
        whole.communicate(timeout=20)
        alone = self.start("alone", "help", slots="1", GRADLE_BOX_MEMORY="24")
        alone.communicate(timeout=20)
        asked = [command.split() for command in self.asked()]
        self.assertEqual([command[command.index("--cpus") + 1] for command in asked], [str(max(cores // 2, 1)), str(cores), str(cores)])

    def test_a_ticket_from_an_older_script_counts_as_the_most_it_asks_for(self):
        # An older checkout's script leaves its ticket empty. This one is held by the test itself.
        queue = self.dir / "state" / "queue"
        queue.mkdir(parents=True)
        old = queue / f"1.{os.getpid()}"
        old.touch()
        fits = self.start("fits", "help", slots=None, GRADLE_BOX_MEMORY="32")
        fits.communicate(timeout=20)
        self.assertTrue((self.dir / "started.fits").exists())
        waits = self.start("waits", "help", slots=None, GRADLE_BOX_MEMORY="33")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.waits").exists())
        old.unlink()
        waits.communicate(timeout=20)
        self.assertTrue((self.dir / "started.waits").exists())

    def test_waits_to_start_while_the_machine_is_out_of_memory(self):
        self.stall(lately=40, total_s=0)
        box = self.start("a", "help")
        time.sleep(1.5)
        self.assertFalse((self.dir / "started.a").exists())
        self.stall(lately=0, total_s=0)
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertEqual(output.count("waiting, the machine is out of memory, stalled 40% of the last 10 s"), 1, output)
        self.assertTrue((self.dir / "started.a").exists())

    def test_starts_at_once_with_some_memory_pressure_under_the_limit(self):
        self.stall(lately=10, total_s=0)
        box = self.start("a", "help")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("waiting", output)

    def test_the_limit_on_memory_pressure_can_be_changed(self):
        self.stall(lately=10, total_s=0)
        box = self.start("a", "help", GRADLE_BOX_STALL_LIMIT="5")
        time.sleep(1.5)
        self.assertFalse((self.dir / "started.a").exists())
        self.stall(lately=0, total_s=0)
        output, _ = box.communicate(timeout=20)
        self.assertIn("stalled 10% of the last 10 s", output)

    def test_says_when_the_machine_ran_out_of_memory_during_the_build(self):
        self.stall(lately=0, total_s=100)
        self.hold("a")
        box = self.start("a", "help", FAKE_EXIT="1")
        self.wait_for("started.a")
        self.stall(lately=0, total_s=112)
        self.release("a")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 1, output)
        self.assertIn("gradle-box: the machine was out of memory for 12 s of this build.", output)

    def test_says_nothing_of_memory_when_the_build_barely_waited_on_it(self):
        self.stall(lately=0, total_s=100)
        self.hold("a")
        box = self.start("a", "help")
        self.wait_for("started.a")
        self.stall(lately=0, total_s=104)
        self.release("a")
        output, _ = box.communicate(timeout=20)
        self.assertNotIn("out of memory", output)

    def test_a_machine_that_does_not_tell_its_memory_pressure_builds_as_before(self):
        box = self.start("a", "help", GRADLE_BOX_PRESSURE=str(self.dir / "none"))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("memory", output)

    def test_off_linux_the_box_gets_no_more_than_podmans_machine_has(self):
        box = self.start("a", "help", GRADLE_BOX_MEMINFO=str(self.dir / "none"), GRADLE_BOX_MEMORY="16", FAKE_MACHINE="3221225472 3")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertIn("podman's machine has 3.0 GiB, so the build gets that, not 16", output)
        command = self.asked()[0]
        self.assertIn("--cpus 3 ", command)
        self.assertIn("--memory 3072m --memory-swap 3072m", command)

    def test_off_linux_the_box_never_gets_more_than_a_machine_between_whole_gib(self):
        for run, machine, memory, said, limit in (("most", "3865470566 3", "16", "3.6 GiB", "3686m"), ("least", "536870912 1", "1", "0.5 GiB", "512m")):
            box = self.start(run, "help", GRADLE_BOX_MEMINFO=str(self.dir / "none"), GRADLE_BOX_MEMORY=memory, FAKE_MACHINE=machine)
            output, _ = box.communicate(timeout=20)
            self.assertEqual(box.returncode, 0, output)
            self.assertIn(f"podman's machine has {said}", output)
            self.assertIn(f"--memory {limit} --memory-swap {limit}", self.asked()[-1])

    def test_off_linux_a_box_that_fits_podmans_machine_is_told_nothing(self):
        # The Mac's own count isn't the machine's.
        self.tool("nproc", "echo 99\n")
        box = self.start("a", "help", GRADLE_BOX_MEMINFO=str(self.dir / "none"), FAKE_MACHINE="17179869184 8")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("machine", output)
        self.assertTrue((self.dir / "info").exists())
        self.assertIn("--cpus 8 ", self.asked()[0])
        self.assertIn("--memory 1g", self.asked()[0])

    def test_off_linux_says_so_when_podmans_machine_is_off(self):
        box = self.start("a", "help", GRADLE_BOX_MEMINFO=str(self.dir / "none"))
        output, _ = box.communicate(timeout=20)
        self.assertNotEqual(box.returncode, 0)
        self.assertIn("podman machine start", output)
        self.assertEqual(self.asked(), [])

    def test_on_linux_podman_is_not_asked_about_a_machine(self):
        box = self.start("a", "help")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertFalse((self.dir / "info").exists())

    def test_the_sdk_is_found_where_a_mac_keeps_it(self):
        sdk = self.home / "Library" / "Android" / "sdk"
        sdk.mkdir(parents=True)
        self.sdk.rmdir()
        box = self.start("a", "help", ANDROID_HOME=None)
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertIn(f"-v {sdk}:{sdk}:ro", self.asked()[0])

    def test_a_second_build_waits_with_only_a_macs_tools(self):
        self.only_a_macs_tools()
        self.hold("first")
        first = self.start("first", "help")
        self.wait_for("started.first")
        second = self.start("second", "help")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.second").exists())
        self.release("first")
        output, _ = first.communicate(timeout=20)
        self.assertEqual(first.returncode, 0, output)
        output, _ = second.communicate(timeout=20)
        self.assertEqual(second.returncode, 0, output)
        self.assertTrue((self.dir / "started.second").exists())
        self.assertFalse((self.dir / "flock-asked").exists())

    def test_a_slot_another_holds_is_waited_for_with_only_a_macs_tools(self):
        import fcntl
        self.only_a_macs_tools()
        state = self.dir / "state"
        state.mkdir()
        # Held as a build holds it, with no ticket in the queue: only the lock keeps the next out.
        with (state / "slot-1.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            box = self.start("a", "help")
            time.sleep(1.5)
            self.assertFalse((self.dir / "started.a").exists())
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertTrue((self.dir / "started.a").exists())
        self.assertFalse((self.dir / "flock-asked").exists())

    def test_downloads_are_kept_and_read_with_only_a_macs_tools(self):
        self.only_a_macs_tools()
        box = self.start("a", "help", FAKE_DOWNLOADS="1")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("illegal option", output)
        self.assertTrue((self.seed() / "modules-2" / "files-2.1" / "some.jar").exists())
        self.assertEqual([path.name for path in (self.dir / "state").glob("seed.*")], [])
        (self.dir / "state" / "gradle-1" / "wrapper" / "dists" / "gradle.zip").unlink()
        shutil.rmtree(self.dir / "state" / "gradle-1" / "wrapper")
        again = self.start("b", "help")
        output, _ = again.communicate(timeout=20)
        self.assertEqual(again.returncode, 0, output)
        self.assertNotIn("illegal option", output)
        self.assertTrue((self.dir / "state" / "gradle-1" / "wrapper" / "dists" / "gradle.zip").exists())
        self.assertFalse((self.dir / "state" / "gradle-1" / "wrapper" / "wrapper").exists())
        self.assertTrue((self.dir / "cp-shared").exists())

    def test_fewer_cpus_can_be_asked_for_never_more(self):
        box = self.start("fewer", "help", GRADLE_BOX_CPUS="0.5")
        box.communicate(timeout=20)
        more = self.start("more", "help", GRADLE_BOX_CPUS="100000")
        more.communicate(timeout=20)
        fewer_command, more_command = self.asked()
        self.assertIn("--cpus 0.5 ", fewer_command)
        self.assertNotIn("--cpus 100000 ", more_command)

    def test_a_memory_level_to_slow_down_at_is_set_only_when_asked(self):
        box = self.start("slowed", "help", GRADLE_BOX_MEMORY_HIGH="1.5")
        box.communicate(timeout=20)
        plain = self.start("plain", "help")
        plain.communicate(timeout=20)
        slowed_command, plain_command = self.asked()
        self.assertIn(f"--cgroup-conf memory.high={3 * 2**29}", slowed_command)
        self.assertNotIn("memory.high", plain_command)

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

    def test_does_not_create_a_seed_over_the_limit(self):
        box = self.start("a", "help", FAKE_DOWNLOADS="1", GRADLE_BOX_CACHE_BYTES="1")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertTrue((self.dir / "state" / "gradle-1" / "caches" / "modules-2").exists())
        self.assertFalse(self.seed().exists())

    def test_without_kept_downloads_there_is_nothing_to_read(self):
        box = self.start("a", "help")
        box.communicate(timeout=20)
        self.assertNotIn("GRADLE_RO_DEP_CACHE", self.asked()[0])

    def test_cleanup_removes_oldest_idle_home_only_when_over_limit(self):
        state = self.dir / "state"
        for slot, age in ((2, 100), (3, 200)):
            home = state / f"gradle-{slot}"
            home.mkdir(parents=True)
            (home / "data").write_bytes(b"x" * 1048576)
            os.utime(home, (age, age))
        (self.seed() / "modules-2").mkdir(parents=True)
        def allocated(path):
            return sum(p.lstat().st_blocks * 512 for p in path.rglob("*"))

        # Leave room for queue and lock metadata on filesystems with different block sizes.
        limit = allocated(state) - allocated(state / "gradle-2") // 2
        box = self.start("a", "help", GRADLE_BOX_CACHE_BYTES=str(limit))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertFalse((state / "gradle-2").exists())
        self.assertTrue((state / "gradle-3" / "data").exists())
        self.assertTrue(self.seed().exists())
        self.assertIn("gradle-2", output)

    def test_seed_and_old_style_locked_home_are_kept_until_all_slots_are_free(self):
        import fcntl
        state = self.dir / "state"
        home = state / "gradle-2"
        home.mkdir(parents=True)
        (home / "data").write_bytes(b"x" * 8192)
        (self.seed() / "modules-2").mkdir(parents=True)
        (self.seed() / "modules-2" / "data").write_bytes(b"x" * 8192)
        with (state / "slot-2.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            box = self.start("first", "help", GRADLE_BOX_CACHE_BYTES="1")
            output, _ = box.communicate(timeout=20)
            self.assertEqual(box.returncode, 0, output)
            self.assertTrue((home / "data").exists())
            self.assertTrue((self.seed() / "modules-2" / "data").exists())
            self.assertIn("above", output)
        box = self.start("second", "help", GRADLE_BOX_CACHE_BYTES="1")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertFalse(home.exists())
        self.assertTrue((self.seed() / "modules-2" / "data").exists())
        self.assertIn("above", output)

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
