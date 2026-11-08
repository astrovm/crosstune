#!/usr/bin/env python3
"""The emulators Crosstune is tested on, made from emulators.json, so a wiped machine gets them back.

  python3 scripts/emulator/emulator.py create [NAME...] [--replace]   # the image and the emulator, safe to run twice
  python3 scripts/emulator/emulator.py start NAME [--window]          # private sound, hardware graphics, waits for boot
  python3 scripts/emulator/emulator.py apps NAME                      # opens each missing app's Play page, one tap each
  python3 scripts/emulator/emulator.py check [NAME...]                # says what's missing, and fails if anything is

Signing in stays by hand and in no file here: this repo is public. See README.md.
"""
import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

DECLARED = json.loads(Path(__file__).with_name("emulators.json").read_text())
QUIET_SINK = "crosstune_quiet"
BOOT_SECONDS = 300
APP_SECONDS = int(os.environ.get("EMULATOR_APP_WAIT", "180"))


def die(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def sdk():
    places = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if not any(places):
        places = [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
    for place in filter(None, places):
        if (Path(place) / "platform-tools/adb").exists():
            return Path(place)
    die(f"No Android SDK here (looked in {', '.join(str(p) for p in places if p)}). Install it, or set ANDROID_HOME.")


def tool(path):
    found = sdk() / path
    if not found.exists():
        die(f"The Android SDK here has no {path}. Install it with Android Studio's SDK Manager.")
    return str(found)


def avd_home():
    if os.environ.get("ANDROID_AVD_HOME"):
        return Path(os.environ["ANDROID_AVD_HOME"])
    return Path(os.environ.get("ANDROID_USER_HOME", Path.home() / ".android")) / "avd"


def image_of(name):
    abi = os.environ.get("EMULATOR_ABI") or {"amd64": "x86_64", "x86_64": "x86_64"}.get(platform.machine().lower(), "arm64-v8a")
    return DECLARED[name]["image"].format(abi=abi)


def image_path(image):
    return image.replace(";", "/")


def avd_dir(name):
    return avd_home() / f"{name}.avd"


def image_in_avd(name):
    """The image an existing emulator runs on, as a path in the SDK, or None if there's no such emulator."""
    config = avd_dir(name) / "config.ini"
    if not config.exists():
        return None
    found = re.search(r"^image\.sysdir\.1=(.+?)/?\s*$", config.read_text(), re.M)
    return found.group(1) if found else ""


def serial_of(name):
    return f"emulator-{DECLARED[name]['port']}"


def adb(*args, timeout=30):
    return subprocess.run([tool("platform-tools/adb"), *args], capture_output=True, text=True, timeout=timeout).stdout


def shell(name, *args):
    return adb("-s", serial_of(name), "shell", *args).strip()


def running(name):
    return re.search(rf"^{serial_of(name)}\s+device\s*$", adb("devices"), re.M) is not None


def installed(name, package):
    return shell(name, "pm", "path", package).startswith("package:")


def signed_in(name):
    # Only whether there's an account: what dumpsys prints has the address, which never goes on screen or in a log.
    # "type=com.google" alone also matches the list of account types Android knows, signed in or not.
    return re.search(r"Account \{name=[^,]*, type=com\.google\}", shell(name, "dumpsys", "account")) is not None


def missing_apps(name):
    return [(label, package) for label, package in DECLARED[name]["play_apps"].items() if not installed(name, package)]


def write_settings(name):
    config = avd_dir(name) / "config.ini"
    lines = config.read_text().splitlines()
    settings = DECLARED[name]["config"]
    kept = [line for line in lines if line.split("=", 1)[0].strip() not in settings]
    config.write_text("\n".join([*kept, *(f"{key}={value}" for key, value in settings.items())]) + "\n")


def remove_avd(name):
    shutil.rmtree(avd_dir(name))
    (avd_home() / f"{name}.ini").unlink(missing_ok=True)


def create(name, replace):
    image = image_of(name)
    current = image_in_avd(name)
    if current is not None and current != image_path(image):
        if not replace:
            die(f"{name} runs on {current}, not {image_path(image)}. "
                "--replace makes it again, and loses its apps and logins.")
        if running(name):
            die(f"{name} is running. Stop it before replacing it.")
        remove_avd(name)
        current = None
    if not (sdk() / image_path(image)).exists():
        print(f"{name}: installing {image}")
        answer = subprocess.run([tool("cmdline-tools/latest/bin/sdkmanager"), "--install", image],
                                input="y\n" * 20, capture_output=True, text=True)
        if answer.returncode != 0 or not (sdk() / image_path(image)).exists():
            die(f"Couldn't install {image}:\n{answer.stdout[-800:]}{answer.stderr[-800:]}")
    if current is None:
        avd_home().mkdir(parents=True, exist_ok=True)
        answer = subprocess.run(
            [tool("cmdline-tools/latest/bin/avdmanager"), "create", "avd", "-n", name, "-k", image, "-d", DECLARED[name]["device"]],
            input="no\n", capture_output=True, text=True)
        if answer.returncode != 0 or image_in_avd(name) is None:
            die(f"Couldn't create {name}:\n{answer.stdout[-800:]}{answer.stderr[-800:]}")
        print(f"{name}: created")
    else:
        print(f"{name}: already there, its apps and logins kept")
    write_settings(name)


def sound_options(window):
    """Nothing plays out loud: on Linux both the speaker and the mic go through a private sink."""
    hide = [] if window else ["-qt-hide-window"]
    if platform.system() == "Linux" and shutil.which("pactl"):
        sinks = subprocess.run(["pactl", "list", "short", "sinks"], capture_output=True, text=True).stdout
        if QUIET_SINK not in sinks:
            subprocess.run(["pactl", "load-module", "module-null-sink", f"sink_name={QUIET_SINK}"], capture_output=True)
        return hide + ["-audio", "pa", "-allow-host-audio"], {"PULSE_SINK": QUIET_SINK, "PULSE_SOURCE": f"{QUIET_SINK}.monitor"}
    return hide + ["-no-audio"], {}


def start(name, window):
    serial = serial_of(name)
    if running(name):
        hear_host(name)
        print(f"{name} is running already: {serial}")
        return
    if image_in_avd(name) is None:
        die(f"There's no {name} yet. Run: python3 {sys.argv[0]} create {name}")
    options, env = sound_options(window)
    log_path = avd_dir(name) / "started.log"
    with log_path.open("w") as log:
        emulator = subprocess.Popen(
            [tool("emulator/emulator"), "-avd", name, "-port", str(DECLARED[name]["port"]), "-gpu", "host", *options],
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True, env={**os.environ, **env})
    deadline = time.monotonic() + BOOT_SECONDS
    while shell(name, "getprop", "sys.boot_completed") != "1":
        if emulator.poll() not in (None, 0):
            die(f"{name} stopped while starting:\n{log_path.read_text()[-1500:]}")
        if time.monotonic() > deadline:
            die(f"{name} didn't finish starting in {BOOT_SECONDS} s. Its log: {log_path}")
        time.sleep(1)
    hear_host(name)
    print(f"{name} is running: {serial}")


def hear_host(name):
    """The mic hears the private sink only once told to: otherwise it records silence (-50 dB against -14 dB, measured)."""
    adb("-s", serial_of(name), "emu", "avd", "hostmicon")


def apps(name):
    if not running(name):
        die(f"{name} isn't running. Run: python3 {sys.argv[0]} start {name} --window")
    if DECLARED[name]["sign_in"] and not signed_in(name):
        die(f"Sign in to the Google test account on {name} first: the Play Store needs it.")
    for label, package in missing_apps(name):
        print(f"{label}: tap Install on its Play page", flush=True)
        shell(name, "am", "start", "-a", "android.intent.action.VIEW", "-p", "com.android.vending",
              "-d", f"market://details?id={package}")
        deadline = time.monotonic() + APP_SECONDS
        while not installed(name, package) and time.monotonic() < deadline:
            time.sleep(2)
    left = missing_apps(name)
    for label, _ in left:
        print(f"Still missing: {label}")
    if left:
        sys.exit(1)
    print(f"{name} has all its apps")


def check(names):
    problems = 0

    def problem(text):
        nonlocal problems
        problems += 1
        print(f"  Missing: {text}")

    for name in names:
        print(name)
        image = image_of(name)
        current = image_in_avd(name)
        if current is None:
            problem(f"the emulator. Run: python3 {sys.argv[0]} create {name}")
            continue
        if current != image_path(image):
            problem(f"the right image: it runs on {current}, not {image_path(image)}. Run create with --replace")
            continue
        if not (sdk() / current).exists():
            problem(f"its image. Run: python3 {sys.argv[0]} create {name}")
            continue
        if not running(name):
            print(f"  Not running, so its apps and sign-in weren't looked at. Run: python3 {sys.argv[0]} start {name}")
        else:
            if DECLARED[name]["sign_in"] and not signed_in(name):
                problem("Sign in to the Google test account")
            for label, _ in missing_apps(name):
                problem(f"{label}. Run: python3 {sys.argv[0]} apps {name}")
            for label, package in DECLARED[name]["optional_apps"].items():
                if not installed(name, package):
                    print(f"  Optional, not installed: {label}")
        for step in DECLARED[name]["by_hand"]:
            print(f"  By hand, so this can't tell: {step}")
    others = sorted(path.stem for path in avd_home().glob("*.ini") if path.stem not in DECLARED)
    if others:
        print(f"Not declared in emulators.json, so nothing here knows what they're for: {', '.join(others)}")
    if problems:
        sys.exit(1)


def names_given(given):
    unknown = [name for name in given if name not in DECLARED]
    if unknown:
        die(f"No emulator called {', '.join(unknown)}. There are: {', '.join(DECLARED)}")
    return given or list(DECLARED)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("create")
    make.add_argument("names", nargs="*")
    make.add_argument("--replace", action="store_true", help="make it again if it's on another image, losing its data")
    run = commands.add_parser("start")
    run.add_argument("name")
    run.add_argument("--window", action="store_true", help="show it, to sign in or tap Install")
    install = commands.add_parser("apps")
    install.add_argument("name")
    look = commands.add_parser("check")
    look.add_argument("names", nargs="*")
    args = parser.parse_args()

    sdk()
    if args.command == "create":
        for name in names_given(args.names):
            create(name, args.replace)
    elif args.command == "start":
        start(names_given([args.name])[0], args.window)
    elif args.command == "apps":
        apps(names_given([args.name])[0])
    else:
        check(names_given(args.names))


if __name__ == "__main__":
    main()
