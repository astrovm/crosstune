#!/usr/bin/env python3
"""How smooth the dev app is on a phone, measured the same way each time (Issue #884).

    perf.py jank --runs 10                              # janky frames while the main screen scrolls
    perf.py jank --runs 10 --build old=a.apk --build new=b.apk   # the same, two builds in turns
    perf.py cpu --seconds 20 --label lyrics             # main-thread and whole-app CPU, on what's on screen
    perf.py start --runs 10 --build new=b.apk           # cold start, `am start -W`

Each run prints one JSON line, and each build a summary (mean, spread) at the end. Pick the phone
with ANDROID_SERIAL. It needs adb, which scripts/lib/adbtool.py finds: $ADB, the SDK's, or the one on the PATH.

`jank` cold starts the app before each run, so the main screen shows only its Recent list, then
swipes the same way each run: up the list and back down, a fling each, from the screen's own size.
The phone's history is the content, so compare builds on one phone. A list that doesn't scroll
draws next to nothing, so a run with fewer frames than flings stops there.

With --build, each run installs the builds in turn, keeping the app's data, so they take turns on
a phone that warms up and cools down, rather than one build getting the cooler phone. The SDK's
aapt2 (Android SDK Build Tools) checks a private copy of each APK before any install, and each run installs that
checked copy, only once it is confirmed as Crosstune Dev. An APK that
isn't debuggable is then compiled from its baseline profile, as Play has it after an install. Builds that
aren't debuggable need the same versionCode, as Android won't install an older one over a newer one: build the
version before with this one's number to compare them. It stops before any install when they differ.
A debug build runs most of its code in Android's interpreter, so measure a release-like build
(`-PperfBuild :app:assemblePerf`, the minified release as "Crosstune Dev", signed with the debug key) for what
people's phones do. Messages about the phone cooling down go to stderr. When Android gives no cold-start time, the
command stops there, with no value made up for that run.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from contextlib import ExitStack
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import adbtool

PACKAGE = "com.astrovm.crosstune.dev"
ACTIVITY = f"{PACKAGE}/com.astrovm.crosstune.MainActivity"
PROFILE_RECEIVER = f"{PACKAGE}/androidx.profileinstaller.ProfileInstallReceiver"
USER_HZ = 100


def aapt2_path() -> str:
    # The named SDK first, then Android Studio's: Build Tools may be only in the one Studio keeps up to date.
    places = adbtool.sdk_places()
    for sdk in [*places, *(place for place in adbtool.studio_places() if place not in places)]:
        tools = sorted(sdk.glob("build-tools/*/aapt2"), reverse=True)
        if tools:
            return str(tools[0])
    found = shutil.which("aapt2")
    if not found:
        sys.exit("aapt2 is missing: install Android SDK Build Tools to check each APK")
    return found


def check_apk(apk: Path, source: Path) -> None:
    command = [aapt2_path(), "dump", "packagename", str(apk)]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    package = result.stdout.strip()
    if result.returncode or not package:
        sys.exit(f"Cannot read the package in {source}: {result.stderr.strip() or 'no package name'}")
    if package != PACKAGE:
        sys.exit(f"Cannot install {source}: expected {PACKAGE}, found {package}")


def version_of(apk: Path) -> tuple[str, bool]:
    """Its version code, and whether it's debuggable."""
    result = subprocess.run([aapt2_path(), "dump", "badging", str(apk)], check=False, capture_output=True, text=True)
    code = re.search(r"versionCode='([0-9]+)'", result.stdout)
    return (code.group(1) if code else "", "application-debuggable" in result.stdout.splitlines())


def check_turns(builds: list[tuple[str, str]]) -> None:
    """Builds that take turns all install over each other. Android takes an older version code over a newer one
    only for a debuggable build, so a release-like one of the version before stopped at its second install."""
    versions = {label: version_of(Path(apk)) for label, apk in builds}
    if len({code for code, _ in versions.values()}) > 1 and not all(debuggable for _, debuggable in versions.values()):
        listed = ", ".join(f"{label} {code or 'unknown'}" for label, (code, _) in versions.items())
        sys.exit(
            f"Cannot take turns between version codes {listed}: Android installs an older version over a newer one"
            " only when it's debuggable. Build them with the same versionCode."
        )


def staged_builds(arguments: list[str], folder: Path) -> list[tuple[str, str]]:
    builds = []
    for index, argument in enumerate(arguments):
        label, filename = argument.split("=", 1)
        source = Path(filename)
        staged = folder / str(index) / source.name
        staged.parent.mkdir()
        try:
            shutil.copyfile(source, staged)
        except OSError as error:
            sys.exit(f"Cannot copy {source}: {error.strerror}")
        check_apk(staged, source)
        builds.append((label, str(staged)))
    check_turns(builds)
    return builds


def adb(*args: str) -> str:
    return adbtool.run(*args, check=True, capture_output=True, text=True).stdout


def shell(*args: str) -> str:
    return adb("shell", *args)


def gfxinfo(text: str) -> dict:
    """The numbers `dumpsys gfxinfo` gives for the frames since it was last reset."""

    def number(pattern: str) -> int:
        found = re.search(pattern, text)
        return int(found.group(1)) if found else 0

    janky = re.search(r"^Janky frames: (\d+) \(([\d.]+)%\)", text, re.MULTILINE)
    return {
        "frames": number(r"Total frames rendered: (\d+)"),
        "janky": int(janky.group(1)) if janky else 0,
        "janky_pct": float(janky.group(2)) if janky else 0.0,
        "p50_ms": number(r"\b50th percentile: (\d+)ms"),
        "p90_ms": number(r"\b90th percentile: (\d+)ms"),
        "p99_ms": number(r"\b99th percentile: (\d+)ms"),
        "slow_ui": number(r"Number Slow UI thread: (\d+)"),
        "slow_draw": number(r"Number Slow issue draw commands: (\d+)"),
        "missed_vsync": number(r"Number Missed Vsync: (\d+)"),
        "input_latency": number(r"Number High input latency: (\d+)"),
    }


def ticks(stat: str) -> int:
    """utime + stime from a /proc stat line, in clock ticks; the name in brackets may hold spaces."""
    fields = stat[stat.rindex(")") + 2:].split()
    return int(fields[11]) + int(fields[12])


def summary(label: str, runs: list[dict], keys: list[str]) -> dict:
    out: dict = {"label": label, "runs": len(runs)}
    for key in keys:
        values = [run[key] for run in runs]
        out[key] = {
            "mean": round(statistics.mean(values), 2),
            "sd": round(statistics.stdev(values), 2) if len(values) > 1 else 0.0,
            "min": min(values),
            "max": max(values),
        }
    return out


def thermal() -> str:
    found = re.search(r"Thermal Status: (\d+)", shell("dumpsys", "thermalservice"))
    return found.group(1) if found else "?"


def battery_c() -> float | None:
    found = re.search(r"^[ \t]*temperature:[ \t]+(-?[0-9]+)[ \t]*$", shell("dumpsys", "battery"), re.MULTILINE)
    return int(found.group(1)) / 10 if found else None


def cool(below_c: float, status: int) -> None:
    """Waits until the phone's thermal status is at most [status] and its battery is under [below_c]:
    a throttled phone drew the same build's frames a third slower."""
    while True:
        current = thermal()
        battery = battery_c()
        if current.isdigit() and int(current) <= status and battery is not None and battery < below_c:
            return
        thermal_note = f"thermal status {current} (at most {status})" if current.isdigit() else "no thermal status"
        battery_note = f"battery {battery:g} C (below {below_c:g} C)" if battery is not None else "no battery temperature"
        print(f"Waiting to cool: {thermal_note}, {battery_note}", file=sys.stderr, flush=True)
        time.sleep(10)


def install(apk: str) -> None:
    out = adb("install", "-r", "-d", apk)
    if "Success" not in out:
        sys.exit(f"Installing {apk} failed: {out}")
    if "DEBUGGABLE" not in shell("dumpsys", "package", PACKAGE).split("pkgFlags=")[1].split("\n")[0]:
        shell("am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE", "-n", PROFILE_RECEIVER)
        shell("cmd", "package", "compile", "-f", "-m", "speed-profile", PACKAGE)


def cold_start() -> int:
    shell("am", "force-stop", PACKAGE)
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    out = shell("am", "start", "-W", "-n", ACTIVITY)
    found = re.search(r"^TotalTime:[ \t]+([0-9]+)[ \t]*$", out, re.MULTILINE)
    if not found:
        sys.exit("Android did not report TotalTime for the cold start")
    return int(found.group(1))


def screen() -> tuple[int, int]:
    size = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1]
    return int(size[0]), int(size[1])


def jank(args: argparse.Namespace) -> dict:
    width, height = screen()
    x, low, high = str(width // 2), str(height * 3 // 4), str(height // 3)
    cool(args.below, args.status)
    cold_start()
    time.sleep(args.settle)
    shell("dumpsys", "gfxinfo", PACKAGE, "reset")
    for start, end in ((low, high), (high, low)):
        for _ in range(args.swipes):
            shell("input", "swipe", x, start, x, end, "150")
            time.sleep(0.9)
    result = gfxinfo(shell("dumpsys", "gfxinfo", PACKAGE))
    # A list that scrolls draws dozens of frames a fling. Fewer than one each means nothing moved, and
    # the few frames there are would read as all janky.
    if result["frames"] < args.swipes * 2:
        sys.exit(
            f"Only {result['frames']} frames were drawn over {args.swipes * 2} flings: the main screen didn't scroll."
            " Open enough songs in Crosstune Dev for Recent to fill more than a screen."
        )
    return result


def cpu(args: argparse.Namespace) -> dict:
    pid = shell("pidof", PACKAGE).split()[0]
    stats = (f"/proc/{pid}/stat", f"/proc/{pid}/task/{pid}/stat")
    before = shell("cat", *stats).splitlines()
    time.sleep(args.seconds)
    after = shell("cat", *stats).splitlines()

    def per_second(line: int) -> float:
        return round((ticks(after[line]) - ticks(before[line])) * 1000 / USER_HZ / args.seconds, 1)

    return {"main_ms_per_s": per_second(1), "app_ms_per_s": per_second(0)}


def start(args: argparse.Namespace) -> dict:
    cool(args.below, args.status)
    total = cold_start()
    time.sleep(args.settle)
    return {"total_ms": total}


MEASURES = {
    "jank": (jank, ["frames", "janky_pct", "p90_ms", "p99_ms", "slow_ui"]),
    "cpu": (cpu, ["main_ms_per_s", "app_ms_per_s"]),
    "start": (start, ["total_ms"]),
}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="what", required=True)
    for name in MEASURES:
        one = sub.add_parser(name)
        one.add_argument("--label", default="build", help="what the installed build is called, without --build")
        one.add_argument("--runs", type=int, default=10)
        one.add_argument("--settle", type=float, default=4.0, help="seconds after a cold start")
        one.add_argument("--below", type=float, default=40.0, help="battery degrees C to cool to before each run")
        one.add_argument("--status", type=int, default=0, help="the most thermal status to run at; 1 for a phone that charges")
        if name != "cpu":
            one.add_argument("--build", action="append", default=[], metavar="LABEL=APK", help="install each in turn, every run")
        if name == "jank":
            one.add_argument("--swipes", type=int, default=8, help="flings each way")
        if name == "cpu":
            one.add_argument("--seconds", type=float, default=20.0)
    args = parser.parse_args(argv)
    measure, keys = MEASURES[args.what]
    with ExitStack() as resources:
        arguments = getattr(args, "build", [])
        if arguments:
            folder = Path(resources.enter_context(tempfile.TemporaryDirectory(prefix="crosstune-perf-")))
            builds = staged_builds(arguments, folder)
        else:
            builds = [(args.label, None)]
        runs: dict[str, list[dict]] = {label: [] for label, _ in builds}
        for run in range(args.runs):
            for label, apk in builds:
                if apk:
                    install(apk)
                result = {"label": label, "run": run + 1, **measure(args), "thermal": thermal(), "battery_c": battery_c()}
                print(json.dumps(result), flush=True)
                runs[label].append(result)
        for label, done in runs.items():
            print(json.dumps(summary(label, done, keys)), flush=True)


if __name__ == "__main__":
    main()
