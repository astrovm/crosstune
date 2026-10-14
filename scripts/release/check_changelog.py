"""Require the current Android version's English release notes before publishing."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]


def check_changelog(root):
    build = root / "app/build.gradle.kts"
    assignments = re.findall(
        r"^[ \t]*versionCode[ \t]*=[ \t]*([^\r\n]*)",
        build.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    if len(assignments) != 1:
        raise ValueError(f"Expected exactly one versionCode assignment in {build}")
    code = assignments[0].split("//", 1)[0].strip()
    if not re.fullmatch(r"[0-9]+", code) or int(code) <= 0:
        raise ValueError(f"Expected a positive literal versionCode in {build}")
    notes = root / f"fastlane/metadata/android/en-US/changelogs/{int(code)}.txt"
    if not notes.is_file():
        raise ValueError(f"Missing Play Store changelog: add {notes}")
    if not notes.read_text(encoding="utf-8").strip():
        raise ValueError(f"Empty Play Store changelog: write release notes in {notes}")
    return notes


def main(root=ROOT):
    try:
        notes = check_changelog(root)
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    print(f"Play Store changelog present: {notes}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
