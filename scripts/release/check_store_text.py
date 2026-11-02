"""Keep the Play Store texts within Google Play's limits, which reject a longer one at upload."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
METADATA = "fastlane/metadata/android"
# Google Play's limits, in characters.
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500


def too_long(root):
    """Each text over its limit, as (path, length, limit)."""
    found = []
    for path in sorted((root / METADATA).rglob("*.txt")):
        limit = CHANGELOG_LIMIT if path.parent.name == "changelogs" else LIMITS.get(path.name)
        if limit is None:
            continue
        # The newline a file ends with isn't part of the text.
        length = len(path.read_text(encoding="utf-8").rstrip("\n"))
        if length > limit:
            found.append((path.relative_to(root), length, limit))
    return found


def main(root=ROOT):
    found = too_long(root)
    for path, length, limit in found:
        print(f"::error file={path}::{length} characters, Google Play takes at most {limit}", file=sys.stderr)
    if not found:
        print("Play Store texts are within Google Play's limits")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
