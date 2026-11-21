"""The Android SDK and its adb, found the same way on Linux (mr, the pi) and on a Mac, and the Samsung test phone.

A script in a folder of scripts/ imports it with

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
    import adbtool

Where adb is: $ADB when set, else platform-tools/adb in the SDK (`sdk()`), else the adb on the PATH.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# The Samsung test phone, as `adb devices -l` names its model.
SAMSUNG_MODEL = "SM_S908E"


def sdk_places() -> list[Path]:
    """Where an SDK may be: $ANDROID_HOME and $ANDROID_SDK_ROOT when either is set, as that SDK is the one meant, or
    else Android Studio's own places on Linux and on a Mac."""
    named = [Path(place) for place in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")) if place]
    return named or [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]


def sdk() -> Path | None:
    """The first SDK in `sdk_places()` with platform-tools/adb in it, or None."""
    return next((place for place in sdk_places() if (place / "platform-tools/adb").exists()), None)


def path() -> str:
    """adb: $ADB, else the SDK's, else the one on the PATH. Exits with what to install when there's none."""
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = sdk()
    if found:
        return str(found / "platform-tools/adb")
    on_path = shutil.which("adb")
    if not on_path:
        sys.exit("adb is missing: install the Android SDK's platform-tools, or set ANDROID_HOME")
    return on_path


def run(*args: str, serial: str | None = None, check: bool, **kwargs) -> subprocess.CompletedProcess:
    """adb with [args], on [serial] when given, through subprocess.run with [check] and [kwargs] (text, timeout, ...)."""
    return subprocess.run([path(), *(["-s", serial] if serial else []), *args], check=check, **kwargs)


def samsung_serial(listing: str) -> str:
    """The serial of the one online Samsung test phone in [listing], what `adb devices -l` prints. It raises
    ValueError when there's none or more than one, so a script never touches another device."""
    found = []
    for line in listing.splitlines():
        match = re.fullmatch(r"(.+?)\s+device\s+(.+)", line.strip())
        if match and f"model:{SAMSUNG_MODEL}" in match.group(2).split():
            found.append(match.group(1))
    if len(found) != 1:
        raise ValueError(f"Expected exactly one online Samsung {SAMSUNG_MODEL.replace('_', '-')}")
    return found[0]
