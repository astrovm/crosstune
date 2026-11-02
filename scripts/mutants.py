#!/usr/bin/env python3
"""Mutation testing on what a branch changed: PIT changes the app's changed lines a little, one
change at a time, and runs the tests that cover each. A change no test notices is a missing test.

  python3 scripts/mutants.py                        # what this branch changed from origin/main
  python3 scripts/mutants.py HEAD~1                 # from somewhere else
  python3 scripts/mutants.py --gradle "sh run.sh"   # Gradle run some other way, like in a container

Uncommitted changes count too. Lists the changes that lived, by file and line, and exits 1 if any
did; PIT's full report is in app/build/reports/pitest/playDebug/.
"""
import argparse
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = "app/src/main/java/"
LINES = ROOT / "app/build/mutants/changed-lines.txt"
REPORT = ROOT / "app/build/reports/pitest/playDebug/mutations.xml"


def changed_lines(base):
    """Each changed Kotlin line, as "com/astrovm/crosstune/File.kt:12", the way PIT names one."""
    since = subprocess.run(["git", "merge-base", base, "HEAD"], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
    diff = subprocess.run(["git", "diff", "-U0", "--no-color", since, "--", SOURCES], cwd=ROOT, check=True, capture_output=True, text=True).stdout
    lines, file = [], None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[4:].removeprefix("b/")
            file = path.removeprefix(SOURCES) if path.endswith(".kt") else None
        elif line.startswith("@@") and file:
            start, _, count = re.match(r"@@ -\S+ \+(\d+)(,(\d+))? @@", line).groups()
            lines += [f"{file}:{n}" for n in range(int(start), int(start) + int(count or 1))]
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base", nargs="?", default="origin/main")
    parser.add_argument("--gradle", default="./gradlew", help="how to run Gradle")
    args = parser.parse_args()

    lines = changed_lines(args.base)
    if not lines:
        print("No Kotlin lines changed in the app.")
        return 0
    LINES.parent.mkdir(parents=True, exist_ok=True)
    LINES.write_text("\n".join(lines) + "\n")
    print(f"{len(lines)} changed lines in {len({line.split(':')[0] for line in lines})} files.")
    REPORT.unlink(missing_ok=True)
    subprocess.run([*shlex.split(args.gradle), ":app:pitestPlayDebug", f"-PmutateLines={LINES}"], cwd=ROOT, check=True)

    mutations = ET.parse(REPORT).getroot()
    lived = [m for m in mutations if m.get("status") in ("SURVIVED", "NO_COVERAGE")]
    for m in sorted(lived, key=lambda m: (m.findtext("sourceFile"), int(m.findtext("lineNumber")))):
        print(f"{m.findtext('sourceFile')}:{m.findtext('lineNumber')}  {m.get('status').lower().replace('_', ' ')}: {m.findtext('description')}")
    print(f"{len(mutations) - len(lived)} of {len(mutations)} changes caught by a test.")
    return 1 if lived else 0


if __name__ == "__main__":
    sys.exit(main())
