#!/usr/bin/env python3
"""Makes main's readings of the fuzz titles, which TitlesAgainstMainTest holds the app's to.

    python3 scripts/titlefuzz/baseline.py [REF] [--serial SERIAL] [--count N] [--out FILE]
    python3 scripts/titlefuzz/baseline.py [REF] --jvm [--out FILE]
    python3 scripts/titlefuzz/baseline.py [REF] --long [--serial SERIAL]

REF is the commit to read with, origin/main by default. It's checked out in a temporary worktree, under
$TITLEFUZZ_TMP if set; TitleFuzz.kt, PhoneRegex.kt and PhoneRegexTable.kt are copied in from this checkout,
MainTitleProbes.kt as its TitleProbes.kt, and WriteTitlesBaseline.kt.

On a phone, kotlin.text.Regex runs on ICU, which reads spaces, digits, line ends and case otherwise than the JVM.
So by default the readings are made on the crosstune-phone emulator, as on a phone: the checkout is compiled in the
Gradle box, dexed with d8, and run with app_process. Take the emulator first:
python3 scripts/devicelock.py take crosstune-phone --who "title baseline" --minutes 30. It never runs on a phone:
it refuses a serial that isn't an emulator. --jvm reads them on the JVM in the Gradle box instead, to compare.

The readings land in app/src/test/titlefuzz/main.tsv.gz, or --out, with the commit and the engine on the first
line. Make them again when TitleFuzz.kt changes what it makes, or when main's own readings are to be the new floor.
--count reads only the first N titles, to time a run.

--long writes no readings: it times each reader on long titles on the emulator instead, with LongTitlesProbe.kt, at
three lengths. On a phone each look with a pattern copies the whole title first, so a reader that looks once a pass or
once a bracket takes time squared there, where the JVM, and so LongTitlesTest, sees none.

It can't read a commit whose readers MainTitleProbes.kt doesn't reach: then change that file to match.
"""

import argparse
import gzip
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "scripts/regextable"))
import regextable  # noqa: E402

TESTS = Path("app/src/test/java/com/astrovm/crosstune")
MAIN = Path("app/src/main/java/com/astrovm/crosstune")
OUT = ROOT / "app/src/test/titlefuzz/main.tsv.gz"
WRITER = "com.astrovm.crosstune.WriteTitlesBaseline"
DEVICE_JAR = "/data/local/tmp/titles-baseline.jar"
DEVICE_OUT = "/data/local/tmp/titles-baseline.tsv.gz"
# The classes the writer runs: the app's, and the tests' that make and read the titles.
TEST_CLASSES = ("TitleFuzz", "TitleProbes", "WriteTitlesBaseline", "LongTitlesProbe")
TIMER = "com.astrovm.crosstune.LongTitlesProbe"


def git(*args, cwd=ROOT):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def box(checkout, *tasks):
    env = dict(os.environ, GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
    subprocess.run(["nice", "-n", "10", "scripts/gradle-box/gradle-box.sh", "--max-workers=2", *tasks], cwd=checkout, env=env, check=True)


def on_jvm(checkout):
    box(checkout, ":app:testDebugUnitTest", "--tests", "*WriteTitlesBaseline")
    return checkout / "app/build/titles-baseline.tsv.gz"


def on_emulator(checkout, serial, count, long=False):
    serial = regextable.emulator(serial)
    box(checkout, ":app:compileDebugUnitTestKotlin")
    built = checkout / "app/build/intermediates/built_in_kotlinc"
    tests = built / "debugUnitTest/compileDebugUnitTestKotlin/classes"
    work = checkout.parent
    classes = work / "classes"
    shutil.copytree(built / "debug/compileDebugKotlin/classes", classes)
    for name in TEST_CLASSES:
        for found in (tests / "com/astrovm/crosstune").glob(f"{name}*.class"):
            shutil.copy(found, classes / "com/astrovm/crosstune" / found.name)
    shutil.make_archive(str(work / "classes"), "zip", classes)
    stdlib = next(Path.home().glob(".cache/crosstune-gradle-box/seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*[0-9].jar"))
    tools = sorted((regextable.SDK / "build-tools").iterdir())[-1]
    platform = sorted((regextable.SDK / "platforms").iterdir())[-1] / "android.jar"
    # The app's resource ids, which the readers' enums name.
    ids = next(checkout.glob("app/build/intermediates/*r_class_jar/debug/*/R.jar"))
    regextable.run(str(tools / "d8"), "--release", "--min-api", "26", "--lib", str(platform), "--output", str(work / "dex.jar"), str(work / "classes.zip"), str(ids), str(stdlib))
    regextable.run("adb", "-s", serial, "push", str(work / "dex.jar"), DEVICE_JAR)
    try:
        if long:
            subprocess.run(["adb", "-s", serial, "shell", f"app_process -cp {DEVICE_JAR} /system/bin {TIMER}"], check=True)
            return None
        started = time.monotonic()
        subprocess.run(["adb", "-s", serial, "shell", f"app_process -cp {DEVICE_JAR} /system/bin {WRITER} {DEVICE_OUT} {count}"], check=True)
        print(f"{count} titles read on {serial} in {time.monotonic() - started:.0f} s")
        regextable.run("adb", "-s", serial, "pull", DEVICE_OUT, str(work / "made.tsv.gz"))
    finally:
        regextable.run("adb", "-s", serial, "shell", f"rm -f {DEVICE_JAR} {DEVICE_OUT}")
    return work / "made.tsv.gz"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ref", nargs="?", default="origin/main")
    parser.add_argument("--jvm", action="store_true", help="read on the JVM, not on the emulator")
    parser.add_argument("--long", action="store_true", help="time the readers on long titles on the emulator")
    parser.add_argument("--serial")
    parser.add_argument("--count", type=int, default=200_000)
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    commit = git("rev-parse", args.ref)
    place = Path(tempfile.mkdtemp(prefix="crosstune-titlefuzz-", dir=os.environ.get("TITLEFUZZ_TMP")))
    checkout = place / "checkout"
    git("worktree", "add", "--detach", str(checkout), commit)
    try:
        shutil.copy(ROOT / TESTS / "TitleFuzz.kt", checkout / TESTS / "TitleFuzz.kt")
        shutil.copy(HERE / "MainTitleProbes.kt", checkout / TESTS / "TitleProbes.kt")
        shutil.copy(HERE / "WriteTitlesBaseline.kt", checkout / TESTS / "WriteTitlesBaseline.kt")
        shutil.copy(HERE / "LongTitlesProbe.kt", checkout / TESTS / "LongTitlesProbe.kt")
        # TitleFuzz reads main's song as a phone does, so main's checkout gets phoneRegex too.
        for name in ("PhoneRegex.kt", "PhoneRegexTable.kt"):
            shutil.copy(ROOT / MAIN / name, checkout / MAIN / name)
        if args.long:
            on_emulator(checkout, args.serial, args.count, long=True)
            return
        made = on_jvm(checkout) if args.jvm else on_emulator(checkout, args.serial, args.count)
        with gzip.open(made, "rt", encoding="utf-8") as read:
            engine, _, lines = read.read().partition("\n")
        args.out.parent.mkdir(parents=True, exist_ok=True)
        # No time in the file, so the same commit on the same engine makes the same bytes.
        with open(args.out, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as out:
            out.write(f"# {commit} {engine}\n{lines}".encode("utf-8"))
        print(f"{args.out}: {lines.count(chr(10))} titles read at {commit[:8]} on {engine}, {args.out.stat().st_size} bytes")
    finally:
        git("worktree", "remove", "--force", str(checkout))
        shutil.rmtree(place, ignore_errors=True)


if __name__ == "__main__":
    main()
