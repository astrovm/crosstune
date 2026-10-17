"""Fail when the release native library depends on where it was built.

F-Droid builds each tag on its own server, in other folders, and only ships the GitHub APK when its
build matches it byte for byte. The libraries are checked before stripping: the build ID in the
shipped library hashes their debug info, so a path in there changes the APK too.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
LIBRARIES = "app/build/intermediates/cxx/RelWithDebInfo/*/obj/*/libmilkdrop.so"


def ndk_version(root):
    build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    versions = re.findall(r'^[ \t]*ndkVersion[ \t]*=[ \t]*"([^"]+)"', build, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("Expected exactly one ndkVersion in app/build.gradle.kts")
    return versions[0]


def check_native_paths(root):
    libraries = sorted(root.glob(LIBRARIES))
    if not libraries:
        raise ValueError(f"No release native library found at {LIBRARIES}: build the release first")
    # The NDK's own folder name, wherever the SDK is: GitHub's runners and F-Droid's server differ.
    leaks = {"the checkout's path": str(root), "the NDK's path": f"ndk/{ndk_version(root)}/"}
    problems = []
    for library in libraries:
        content = library.read_bytes()
        for what, text in leaks.items():
            if text.encode() in content:
                problems.append(f"{library.relative_to(root)} contains {what} ({text})")
    if problems:
        raise ValueError("; ".join(problems))
    return libraries


def main(root=ROOT):
    try:
        libraries = check_native_paths(root)
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    for library in libraries:
        print(f"Same wherever it's built: {library.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
