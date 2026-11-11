#!/usr/bin/env python3
"""How smooth the dev app is on a phone, measured the same way each time (Issue #884).

    perf.py jank --runs 10                              # janky frames while the main screen scrolls
    perf.py jank --runs 10 --build old=a.apk --build new=b.apk   # the same, two builds in turns
    perf.py cpu --seconds 20 --label lyrics             # main-thread and whole-app CPU, on what's on screen
    perf.py start --runs 10 --build new=b.apk           # cold start, `am start -W`

Each run prints one JSON line, and each build a summary (mean, spread) at the end. Pick the phone
with ANDROID_SERIAL. It needs adb: ANDROID_HOME's, else ~/Android/Sdk's, else the one on the PATH.

`jank` cold starts the app before each run, so the main screen shows only its Recent list, then
swipes the same way each run: up the list and back down, a fling each, from the screen's own size.
The phone's history is the content, so compare builds on one phone.

With --build, each run installs the builds in turn, keeping the app's data, so they take turns on
a phone that warms up and cools down, rather than one build getting the cooler phone. An APK that
isn't debuggable is then compiled from its baseline profile, as Play has it after an install.
A debug build runs most of its code in Android's interpreter, so measure a release-like build
(`-PperfBuild :app:assemblePerf`) for what people's phones do.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.astrovm.crosstune.dev"
ACTIVITY = f"{PACKAGE}/com.astrovm.crosstune.MainActivity"
PROFILE_RECEIVER = f"{PACKAGE}/androidx.profileinstaller.ProfileInstallReceiver"
USER_HZ = 100


def adb_path() -> str:
    for base in (os.environ.get("ANDROID_HOME"), str(Path.home() / "Android/Sdk")):
        if base and (Path(base) / "platform-tools/adb").exists():
            return str(Path(base) / "platform-tools/adb")
    found = shutil.which("adb")
    if not found:
        sys.exit("adb is missing: install the Android SDK's platform-tools")
    return found


def adb(*args: str) -> str:
    return subprocess.run([adb_path(), *args], check=True, capture_output=True, text=True).stdout


def shell(*args: str) -> str:
    return adb("shell", *args)


def gfxinfo(text: str) -> dict:
    """The numbers `dumpsys gfxinfo` gives for the frames since it was last reset."""

    def number(pattern: str) -> int:
        found = re.search(pattern, text)
        return int(found.group(1)) if found else 0

    janky = re.search(r"^Janky frames: (\d+) \(([\d.]+)%\)", text, re.M)
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


def battery_c() -> float:
    found = re.search(r"^\s*temperature: (\d+)", shell("dumpsys", "battery"), re.M)
    return int(found.group(1)) / 10 if found else 0.0


def cool(below_c: float, status: int) -> None:
    """Waits until the phone's thermal status is at most [status] and its battery is under [below_c]:
    a throttled phone drew the same build's frames a third slower."""
    while not thermal().isdigit() or int(thermal()) > status or battery_c() >= below_c:
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
    found = re.search(r"TotalTime: (\d+)", out)
    return int(found.group(1)) if found else -1


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
    return gfxinfo(shell("dumpsys", "gfxinfo", PACKAGE))


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
    builds = [tuple(build.split("=", 1)) for build in getattr(args, "build", [])] or [(args.label, None)]
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
