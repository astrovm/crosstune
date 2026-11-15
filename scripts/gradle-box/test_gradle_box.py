"""Runs gradle-box.sh for real, with a stand-in podman that says what it was asked and waits when told."""
import hashlib
import gzip
import os
import shutil
import shlex
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

BOX = Path(__file__).with_name("gradle-box.sh")
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
        self.checkouts = {}

    def checkout(self, name):
        if name not in self.checkouts:
            root = self.dir / ("checkout " + name)
            root.mkdir()
            subprocess.run(["git", "init", "--quiet", str(root)], check=True,
                           env={**os.environ, "HOME": str(self.home)})
            shutil.copytree(BOX.parent, root / "scripts" / "gradle-box")
            self.checkouts[name] = root
        return self.checkouts[name]

    def start(self, run, *args, slots="1", checkout=None, **env):
        root = self.checkout(checkout or run)
        process = subprocess.Popen(
            [str(root / "scripts" / "gradle-box" / BOX.name), *args],
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
        self.addCleanup(self.stop, process)
        return process

    @staticmethod
    def stop(process):
        if process.poll() is None:
            process.terminate()
        process.communicate(timeout=20)

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

    def wait_for(self, name, process=None):
        deadline = time.monotonic() + 20
        while not (self.dir / name).exists():
            if process is not None and process.poll() is not None:
                if (self.dir / name).exists():
                    return
                output, _ = process.communicate(timeout=20)
                self.fail(f"{name} never showed up: exit {process.returncode}\n{output}")
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

    def box_that_runs(self):
        """A podman that runs what the box is asked in its working folder, with a gradlew that says where it ran."""
        podman = self.dir / "bin" / "podman"
        podman.write_text(f"""#!{sys.executable}
import os, subprocess, sys
args = sys.argv[1:]
if args[:1] != ["run"]:
    sys.exit(0)
with open(os.path.join(os.environ["FAKE_DIR"], "asked"), "a") as asked:
    asked.write(" ".join(args) + "\\n")
at = next(i for i, arg in enumerate(args) if arg.startswith("localhost/crosstune-gradle-box:"))
command = args[at + 1:]
# The script the box runs, with this gradlew for the checkout's own.
for i, arg in enumerate(command):
    if arg.endswith("each-checkout.sh"):
        copy = os.path.join(os.environ["FAKE_DIR"], "each-checkout.sh")
        with open(arg) as real, open(copy, "w") as fake:
            fake.write(real.read().replace("./gradlew", os.environ["FAKE_GRADLEW"]))
        command[i] = copy
sys.exit(subprocess.call(command, cwd=args[args.index("-w") + 1]))
""")
        gradlew = self.dir / "gradlew"
        gradlew.write_text('#!/bin/sh\necho "$PWD $*" >> "$FAKE_DIR/ran"\n[ "$PWD" = "$FAKE_FAIL_IN" ] && exit 3\n[ "$PWD" = "$FAKE_FAIL_TOO" ] && exit 5\nexit 0\n')
        gradlew.chmod(gradlew.stat().st_mode | stat.S_IXUSR)
        return gradlew

    def test_also_runs_gradle_in_each_other_checkout_in_the_same_box_and_fails_with_the_first_failure(self):
        gradlew = self.box_that_runs()
        others = [self.dir / "base", self.dir / "other"]
        for other in others:
            other.mkdir()
        box = self.start("also", *(f"--also={other}" for other in others), ":app:songCheck", "-Pa=b c", FAKE_GRADLEW=str(gradlew))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        # One box, which sees each checkout, and Gradle in each in turn with the same arguments.
        self.assertEqual(1, len(self.asked()))
        for other in others:
            self.assertIn(f"-v {other}:{other}", self.asked()[0])
        ran = (self.dir / "ran").read_text().splitlines()
        self.assertEqual([f"{where} --no-daemon :app:songCheck -Pa=b c" for where in (self.checkout("also"), *others)], ran)
        # A failure in one still runs the rest, and the first one is the exit code.
        (self.dir / "ran").unlink()
        box = self.start("also", *(f"--also={other}" for other in others), "help", FAKE_GRADLEW=str(gradlew), FAKE_FAIL_IN=str(others[0]), FAKE_FAIL_TOO=str(others[1]))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(3, box.returncode, output)
        self.assertEqual(3, len((self.dir / "ran").read_text().splitlines()))

    def test_repeated_also_paths_run_and_mount_each_checkout_once(self):
        gradlew = self.box_that_runs()
        root, other = self.checkout("also"), self.checkout("other")
        box = self.start("also", f"--also={root}", f"--also={other}", f"--also={other}", "help", FAKE_GRADLEW=str(gradlew))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(0, box.returncode, output)
        self.assertEqual([f"{where} --no-daemon help" for where in (root, other)],
                         (self.dir / "ran").read_text().splitlines())
        command = self.asked()[0]
        self.assertEqual(1, command.count(f"-v {root}:{root}"))
        self.assertEqual(1, command.count(f"-v {other}:{other}"))

    def test_also_a_folder_that_is_not_there_stops_before_the_box(self):
        box = self.start("missing", f"--also={self.dir / 'nowhere'}", "help")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(1, box.returncode, output)
        self.assertIn("--also: no folder at", output)
        self.assertEqual([], self.asked())
    def test_a_chosen_matcher_replay_folder_is_mounted_and_named_with_its_label(self):
        kept = self.dir / "matchcheck"
        kept.mkdir()
        box = self.start("matchcheck", ":app:testDebugUnitTest", MATCHCHECK_DIR=str(kept), MATCHCHECK_LABEL="main")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertIn(f"-v {kept}:{kept}", self.asked()[0])
        self.assertIn(f"-e MATCHCHECK_DIR={kept}", self.asked()[0])
        self.assertIn("-e MATCHCHECK_LABEL=main", self.asked()[0])

    def test_a_matcher_replay_with_no_label_is_head(self):
        kept = self.dir / "matchcheck"
        kept.mkdir()
        box = self.start("matchcheck", ":app:testDebugUnitTest", MATCHCHECK_DIR=str(kept))
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertIn("-e MATCHCHECK_LABEL=head", self.asked()[0])

    def test_no_replay_folder_is_mounted_unless_chosen(self):
        box = self.start("plain", ":app:assembleDebug")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        self.assertNotIn("SYNCREPLAY_DIR", self.asked()[0])
        self.assertNotIn("MATCHCHECK", self.asked()[0])

    def test_runs_gradle_in_the_container_with_only_what_it_needs(self):
        box = self.start("a", ":app:assembleDebug", "--stacktrace")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(box.returncode, 0, output)
        command = self.asked()[0]
        self.assertTrue(command.split(" ", 2)[2].startswith("--rm "), command)
        self.assertIn(f"-v {self.sdk}:{self.sdk}:ro", command)
        self.assertIn(f"-v {self.checkout('a')}:{self.checkout('a')}", command)
        self.assertIn(f"-w {self.checkout('a')}", command)
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
        self.assertIn(str(self.checkout("a") / "scripts" / "gradle-box" / "Containerfile"), build)
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

    def test_same_checkout_waits_even_when_memory_and_slots_fit(self):
        self.hold("first")
        first = self.start("first", "help", slots="2", checkout="shared")
        self.wait_for("started.first")
        second = self.start("second", "help", slots="2", checkout="shared")
        time.sleep(0.5)
        self.assertFalse((self.dir / "started.second").exists())
        self.release("first")
        first.communicate(timeout=20)
        output, _ = second.communicate(timeout=20)
        self.assertEqual(0, second.returncode, output)
        self.assertIn("checkout is already in use", output)

    def test_a_build_cannot_overtake_a_ticket_before_it_is_published(self):
        publish = self.dir / "hold-publish"
        publish.touch()
        self.addCleanup(lambda: publish.unlink(missing_ok=True))
        self.tool("mv", '\n'.join([
            'if [ "$FAKE_RUN" = first ]; then',
            'case "$2" in */queue/*)',
            'touch "$FAKE_DIR/publishing.first"',
            'while [ -e "$FAKE_DIR/hold-publish" ]; do sleep 0.02; done',
            ';; esac; fi',
            'exec ' + shlex.quote(shutil.which("mv")) + ' "$@"',
        ]))
        self.hold("first")
        first = self.start("first", "help", slots="2", checkout="shared")
        try:
            self.wait_for("publishing.first")
            second = self.start("second", "help", slots="2", checkout="shared")
            time.sleep(0.5)
            self.assertFalse((self.dir / "started.second").exists())
            publish.unlink()
            self.wait_for("started.first")
            self.assertFalse((self.dir / "started.second").exists())
        finally:
            publish.unlink(missing_ok=True)
            (self.dir / "hold.first").unlink(missing_ok=True)
        first.communicate(timeout=20)
        output, _ = second.communicate(timeout=20)
        self.assertEqual(0, second.returncode, output)

    def test_a_running_build_keeps_its_script_when_the_file_is_edited(self):
        self.hold("first")
        first = self.start("first", "help")
        self.wait_for("started.first")
        script = self.checkout("first") / "scripts" / "gradle-box" / BOX.name
        # Move every later byte offset while the stand-in build is held.
        script.write_text("exit 99\n" * 8_192 + script.read_text())
        self.release("first")
        output, _ = first.communicate(timeout=20)
        self.assertEqual(0, first.returncode, output)
        self.assertEqual(1, len(self.asked()))

    def test_a_backward_or_unchanged_clock_cannot_overtake_a_running_build(self):
        self.tool("date", 'if [ "$1" = +%s%N ]; then echo "$FAKE_NOW"; else exec ' +
                  shlex.quote(shutil.which("date")) + ' "$@"; fi\n')
        for second_time in ("1000000000000000000", "2000000000000000000"):
            with self.subTest(second_time=second_time):
                self.hold("first")
                self.hold("second")
                first = self.start("first", "help", slots="2", checkout="shared", FAKE_NOW="2000000000000000000")
                self.wait_for("started.first")
                second = self.start("second", "help", slots="2", checkout="shared", FAKE_NOW=second_time)
                try:
                    queue = self.dir / "state/queue"
                    deadline = time.monotonic() + 20
                    while len(list(queue.iterdir())) < 2:
                        self.assertLess(time.monotonic(), deadline, "second ticket was not published")
                        time.sleep(0.02)
                    self.assertFalse((self.dir / "started.second").exists())
                    stamps = {int(p.name.rsplit(".", 1)[1]): int(p.name.rsplit(".", 1)[0]) for p in queue.iterdir()}
                    self.assertGreater(stamps[second.pid], stamps[first.pid])
                finally:
                    self.release("first")
                    self.release("second")
                    first.communicate(timeout=20)
                    output, _ = second.communicate(timeout=20)
                    for name in ("started.first", "started.second"):
                        (self.dir / name).unlink(missing_ok=True)
                self.assertEqual(0, second.returncode, output)

    def test_an_also_build_keeps_its_helper_when_the_file_is_edited(self):
        gradlew = self.box_that_runs()
        gradlew.write_text('#!/bin/sh\necho "$PWD $*" >> "$FAKE_DIR/ran"\n'
                           'touch "$FAKE_DIR/started.first"\n'
                           'while [ -e "$FAKE_DIR/hold.first" ]; do sleep 0.02; done\nexit 0\n')
        self.hold("first")
        other = self.checkout("other")
        box = self.start("first", f"--also={other}", "help", FAKE_GRADLEW=str(gradlew))
        self.wait_for("started.first")
        # box_that_runs copies the helper to stand in for the container's mounted file.
        script = self.dir / "each-checkout.sh"
        script.write_text("exit 99\n" * 8_192 + script.read_text())
        self.release("first")
        output, _ = box.communicate(timeout=20)
        self.assertEqual(0, box.returncode, output)
        self.assertEqual([f"{root} --no-daemon help" for root in (self.checkout("first"), other)],
                         (self.dir / "ran").read_text().splitlines())

    def start_script(self, script, *args, interruptible=False, **extra):
        def reset_signals():
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                signal.signal(sig, signal.SIG_DFL)
        process = subprocess.Popen([str(script), *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                   start_new_session=interruptible, preexec_fn=reset_signals if interruptible else None,
                                   env={**os.environ, "PATH": f"{self.dir / 'bin'}:{os.environ['PATH']}",
                                        "HOME": str(self.home), "FAKE_DIR": str(self.dir), **extra})
        self.addCleanup(self.stop, process)
        return process

    def test_replay_setup_finishes_all_installs_after_an_in_place_edit(self):
        self.tool("uv", 'echo "$*" >> "$FAKE_DIR/installs"\n'
                  'if [ "$1" = venv ]; then\n'
                  'for folder in "$@"; do :; done; mkdir -p "$folder/bin"; touch "$folder/bin/python"; chmod +x "$folder/bin/python"\n'
                  'touch "$FAKE_DIR/started.first"\n'
                  'while [ -e "$FAKE_DIR/hold.first" ]; do /bin/sleep 0.02; done\nfi\n')
        for tool in ("ffmpeg", "yt-dlp", "cmake"):
            self.tool(tool, 'exit 0\n')
        self.tool("uname", 'case "$1" in -s) echo Linux;; -m) echo x86_64;; esac\n')
        for rocm, count in (("0", 4), ("1", 7)):
            with self.subTest(rocm=rocm):
                script = self.dir / "setup.sh"
                shutil.copy2(BOX.parent.parent / "syncreplay/setup.sh", script)
                self.hold("first")
                job = self.start_script(script, REPLAY_CACHE=str(self.dir / ("replay" + rocm)), REPLAY_ROCM=rocm)
                try:
                    self.wait_for("started.first", job)
                    script.write_text("exit 99\n" * 8_192 + script.read_text())
                finally:
                    self.release("first")
                    output, _ = job.communicate(timeout=20)
                    (self.dir / "started.first").unlink(missing_ok=True)
                    installs = self.dir / "installs"
                    calls = installs.read_text().splitlines() if installs.exists() else []
                    installs.unlink(missing_ok=True)
                self.assertEqual(0, job.returncode, output)
                self.assertEqual(count, len(calls), calls)
                self.assertIn("openai-whisper" if rocm == "0" else "triton-rocm==3.8.0", calls[-1])

    def test_recording_help_keeps_only_the_user_instructions(self):
        for name, heading in (("record.sh", "Records the emulator"), ("emulator.sh", "Gets an emulator ready")):
            with self.subTest(name=name):
                script = BOX.parent.parent / "record" / name
                job = self.start_script(script)
                output, _ = job.communicate(timeout=20)
                self.assertEqual(2, job.returncode, output)
                self.assertIn(heading, output)
                self.assertNotIn("Parse the", output)

    def icon_writer(self):
        folder = self.dir / "extension"
        (folder / "scripts").mkdir(parents=True)
        (folder / "icons").mkdir()
        script = folder / "scripts/icons.sh"
        shutil.copy2(BOX.parent.parent.parent / "extension/scripts/icons.sh", script)
        tool = self.dir / "bin/magick"
        tool.write_text(f'''#!{sys.executable}
import os, sys, time
from pathlib import Path
root = Path(os.environ["FAKE_DIR"])
run = os.environ["FAKE_RUN"]
args = sys.argv[1:]
if "-extent" in args:
    source = Path(args[0]).resolve()
    (root / (run + ".input")).write_text(str(source))
    (root / ("started." + run)).touch()
    while (root / ("hold." + run)).exists(): time.sleep(0.02)
    if os.environ.get("FAIL_MAGICK"): sys.exit(7)
    if source.read_text() != run:
        print("another run replaced the temporary image", file=sys.stderr)
        sys.exit(23)
Path(args[-1].removeprefix("PNG32:")).write_text(run)
''')
        tool.chmod(0o755)
        return script

    def test_icon_writers_keep_separate_images_when_they_run_together(self):
        script = self.icon_writer()
        self.hold("a")
        self.hold("b")
        first = self.start_script("/bin/sh", str(script), FAKE_RUN="a")
        self.wait_for("started.a", first)
        second = self.start_script("/bin/sh", str(script), FAKE_RUN="b")
        try:
            self.wait_for("started.b", second)
            a = Path((self.dir / "a.input").read_text())
            b = Path((self.dir / "b.input").read_text())
            self.assertNotEqual(a, b)
        finally:
            self.release("a")
            self.release("b")
            first_output, _ = first.communicate(timeout=20)
            second_output, _ = second.communicate(timeout=20)
        self.assertEqual(0, first.returncode, first_output)
        self.assertEqual(0, second.returncode, second_output)
        self.assertFalse(a.exists())
        self.assertFalse(b.exists())

    def test_an_icon_writer_removes_its_image_when_conversion_fails(self):
        script = self.icon_writer()
        job = self.start_script("/bin/sh", str(script), FAKE_RUN="failed", FAIL_MAGICK="1")
        output, _ = job.communicate(timeout=20)
        self.assertEqual(7, job.returncode, output)
        temporary = Path((self.dir / "failed.input").read_text())
        try:
            self.assertFalse(temporary.exists())
        finally:
            temporary.unlink(missing_ok=True)

    def test_an_interrupted_icon_writer_cleans_up_and_keeps_the_signal_status(self):
        script = self.icon_writer()
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            for group in (True, False):
                with self.subTest(signal=sig, group=group):
                    run = f"signal-{int(sig)}-{group}"
                    self.hold(run)
                    job = self.start_script("/bin/sh", str(script), interruptible=True, FAKE_RUN=run)
                    try:
                        self.wait_for("started." + run, job)
                        temporary = Path((self.dir / (run + ".input")).read_text())
                        if group:
                            os.killpg(job.pid, sig)
                        else:
                            job.send_signal(sig)
                    finally:
                        self.release(run)
                        try:
                            output, _ = job.communicate(timeout=20)
                        except subprocess.TimeoutExpired:
                            os.killpg(job.pid, signal.SIGKILL)
                            job.communicate(timeout=20)
                            raise
                    self.assertFalse(temporary.exists())
                    self.assertEqual(-int(sig), job.returncode, output)

    def test_interrupting_icon_generation_stops_a_calling_shell(self):
        script = self.icon_writer()
        caller = self.dir / "caller.sh"
        caller.write_text('#!/bin/bash\n/bin/sh "$1"\necho continued > "$FAKE_DIR/continued"\n')
        self.hold("caller")
        job = self.start_script("/bin/bash", str(caller), str(script), interruptible=True, FAKE_RUN="caller")
        try:
            self.wait_for("started.caller", job)
            temporary = Path((self.dir / "caller.input").read_text())
            os.killpg(job.pid, signal.SIGINT)
        finally:
            self.release("caller")
            try:
                output, _ = job.communicate(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(job.pid, signal.SIGKILL)
                job.communicate(timeout=20)
                raise
        self.assertFalse((self.dir / "continued").exists(), output)
        self.assertNotEqual(0, job.returncode, output)
        self.assertFalse(temporary.exists())

    def test_golden_writers_finish_and_remove_their_tree_after_an_in_place_edit(self):
        writer = self.dir / "writer"
        writer.write_text('#!/bin/sh\nmkdir -p app/build\nprintf "fake row\\n" > "app/build/$FAKE_TABLE-golden.tsv"\n'
                          'touch "$FAKE_DIR/started.first"\n'
                          'while [ -e "$FAKE_DIR/hold.first" ]; do sleep 0.02; done\nexit 0\n')
        writer.chmod(0o755)
        self.tool("git", 'case "$4" in\nadd)\n'
                  'mkdir -p "$6/scripts/gradle-box" "$6/app/src/test/java/com/astrovm/crosstune"\n'
                  'cp "$FAKE_WRITER" "$6/scripts/gradle-box/gradle-box.sh";;\n'
                  'remove) rm -rf "$6";;\nesac\n')
        for table, source, helper in (("names", "NamePairs.kt", "NamesGoldenWriter.kt"),
                                      ("play", "PlayScenes.kt", "PlayGoldenWriter.kt")):
            with self.subTest(table=table):
                root = self.checkout(table)
                here = root / "scripts" / (table + "check")
                here.mkdir()
                script = here / "golden.sh"
                shutil.copy2(BOX.parent.parent / (table + "check") / "golden.sh", script)
                (here / helper).touch()
                tests = root / "app/src/test/java/com/astrovm/crosstune"
                tests.mkdir(parents=True)
                (tests / source).touch()
                resources = root / "app/src/test/resources"
                resources.mkdir()
                self.hold("first")
                job = self.start_script(script, "fake-sha", FAKE_WRITER=str(writer), FAKE_TABLE=table)
                try:
                    self.wait_for("started.first")
                    script.write_text("exit 99\n" * 8_192 + script.read_text())
                finally:
                    self.release("first")
                    output, _ = job.communicate(timeout=20)
                    (self.dir / "started.first").unlink(missing_ok=True)
                self.assertEqual(0, job.returncode, output)
                self.assertEqual("fake row\n", gzip.decompress((resources / (table + "-golden.tsv.gz")).read_bytes()).decode())
                self.assertFalse((self.home / ".cache/agent-cache" / ("crosstune-" + table + "check-fake-sha")).exists())

    def test_a_recording_finishes_and_restores_animation_after_an_in_place_edit(self):
        script = self.dir / "record.sh"
        shutil.copy2(BOX.parent.parent / "record/record.sh", script)
        self.tool("adb", 'echo "$*" >> "$FAKE_DIR/settings"\n')
        self.tool("sleep", 'case "$1" in 1|1.5) exit 0;; esac\nexec ' + shlex.quote(shutil.which("sleep")) + ' "$@"\n')
        self.tool("scrcpy", 'echo "$$" > "$FAKE_DIR/recorder.pid"\ntrap \'exit 0\' TERM\nwhile :; do sleep 0.02; done\n')
        self.tool("ffprobe", 'echo 1.0\n')
        self.tool("ffmpeg", 'case "$*" in *mpdecimate*) printf "pts_time:0.00\\npts_time:0.016\\npts_time:0.032\\n" >&2;;\n'
                  '*) for last in "$@"; do :; done; touch "$last";; esac\n')
        command = self.dir / "drive"
        command.write_text('#!/bin/sh\ntouch "$FAKE_DIR/started.first"\n'
                           'while [ -e "$FAKE_DIR/hold.first" ]; do sleep 0.02; done\n')
        command.chmod(0o755)
        self.hold("first")
        target = self.dir / "take.mp4"
        job = self.start_script(script, str(target), str(command))
        self.wait_for("started.first")
        script.write_text("exit 99\n" * 8_192 + script.read_text())
        self.release("first")
        output, _ = job.communicate(timeout=20)
        self.assertEqual(0, job.returncode, output)
        self.assertTrue(target.exists())
        self.assertIn("2 moving frames, 0 late", output)
        settings = (self.dir / "settings").read_text().splitlines()
        self.assertEqual([f"shell settings put global {name} 1" for name in
                          ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")], settings[-3:])
        # A failed driving command must restore settings and reap its recorder too.
        shutil.copy2(BOX.parent.parent / "record/record.sh", script)
        command.write_text('#!/bin/sh\nwhile [ ! -f "$FAKE_DIR/recorder.pid" ]; do sleep 0.02; done\nexit 3\n')
        (self.dir / "recorder.pid").unlink()
        job = self.start_script(script, str(target), str(command))
        output, _ = job.communicate(timeout=20)
        self.assertEqual(3, job.returncode, output)
        self.assertEqual(settings[-3:], (self.dir / "settings").read_text().splitlines()[-3:])
        self.wait_for("recorder.pid")
        recorder = int((self.dir / "recorder.pid").read_text())
        try:
            with self.assertRaises(ProcessLookupError):
                os.kill(recorder, 0)
        finally:
            try:
                os.kill(recorder, signal.SIGTERM)
            except ProcessLookupError:
                pass

    def test_memory_tickets_stay_readable_to_older_checkouts(self):
        self.hold("first")
        first = self.start("first", "help", GRADLE_BOX_MEMORY="7")
        self.wait_for("started.first")
        tickets = list((self.dir / "state" / "queue").iterdir())
        self.assertEqual(1, len(tickets))
        self.assertEqual(7, int(tickets[0].read_text()))
        self.release("first")
        first.communicate(timeout=20)
        self.assertEqual([], list((self.dir / "state" / "checkouts").iterdir()))

    def test_an_intermediate_multi_line_ticket_still_protects_its_checkout(self):
        root = self.checkout("shared")
        queue = self.dir / "state" / "queue"
        queue.mkdir(parents=True)
        old = queue / f"1.{os.getpid()}"
        old.write_text(f"1\n{root}\n")
        box = self.start("second", "help", slots="2", checkout="shared")
        try:
            time.sleep(0.5)
            self.assertFalse((self.dir / "started.second").exists())
        finally:
            old.unlink(missing_ok=True)
        output, _ = box.communicate(timeout=20)
        self.assertEqual(0, box.returncode, output)
        self.assertIn("checkout is already in use", output)

    def test_also_checkout_overlap_waits_in_both_directions(self):
        for first_also in (True, False):
            shared = self.checkout("shared")
            first_args = [f"--also={shared}"] if first_also else []
            second_args = [] if first_also else [f"--also={shared}"]
            first_name, second_name = str(first_also) + "first", str(first_also) + "second"
            self.hold(first_name)
            first = self.start(first_name, *first_args, "help", slots="2", checkout="other" if first_also else "shared")
            self.wait_for("started." + first_name)
            second = self.start(second_name, *second_args, "help", slots="2", checkout="shared" if first_also else "other")
            time.sleep(0.5)
            self.assertFalse((self.dir / ("started." + second_name)).exists())
            self.release(first_name)
            first.communicate(timeout=20)
            output, _ = second.communicate(timeout=20)
            self.assertEqual(0, second.returncode, output)
            self.assertIn("checkout is already in use", output)

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
