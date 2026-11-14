#!/usr/bin/env python3
"""One part of mutation testing on all of the app's code, for the weekly run: every Kotlin line,
in order, split into N equal parts. PIT changes the part's lines a little, one change at a time,
through the same plugin scripts/mutants.py uses on a branch's diff.

  python3 scripts/mutants-part/mutants_part.py 3 64
  python3 scripts/mutants-part/mutants_part.py 3 64 --gradle "sh run.sh" --lived lived.txt

Lists the changes that lived, by file and line, and writes them to --lived. Exits 0 when the run
got to its tally, whatever lived; only a broken run fails.
"""
import argparse
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "app/src/main/java"
LINES = ROOT / "app/build/mutants/part-lines.txt"
REPORT = ROOT / "app/build/reports/pitest/debug/mutations.xml"
# The app's code: Kotlin, and the few classes in Java that Kotlin would add bridges to which PIT never sees run.
CODE = (".kt", ".java")


def all_lines(sources=SOURCES):
    """Every line of every file of code, as "com/astrovm/crosstune/File.kt:12", the way PIT names one."""
    lines = []
    for file in sorted(path for path in sources.rglob("*") if path.suffix in CODE):
        name = file.relative_to(sources).as_posix()
        count = len(file.read_text(encoding="utf-8").splitlines())
        lines += [f"{name}:{n}" for n in range(1, count + 1)]
    return lines


def part(lines, k, n):
    """The kth of n parts, 0-based, as equal as whole lines allow."""
    if not 0 <= k < n:
        raise ValueError(f"part {k} isn't one of 0 to {n - 1}")
    return lines[len(lines) * k // n : len(lines) * (k + 1) // n]


def lived(report):
    """What no test killed, "File.kt:12  survived: description", by file and line, and how many changes there were.
    Anything but KILLED counts: a change that ran out of memory or broke the run was not caught either, and
    must not vanish from the list while the job says success."""
    mutations = ET.parse(report).getroot()
    left = [m for m in mutations if m.get("status") != "KILLED"]
    left.sort(key=lambda m: (m.findtext("sourceFile"), int(m.findtext("lineNumber"))))
    rows = [
        f"{m.findtext('sourceFile')}:{m.findtext('lineNumber')}  {m.get('status').lower().replace('_', ' ')}: {m.findtext('description')} [class={m.findtext('mutatedClass')} method={m.findtext('mutatedMethod')}{m.findtext('methodDescription')} mutator={m.findtext('mutator')} index={','.join(e.text for e in m.findall('indexes/index'))} block={','.join(e.text for e in m.findall('blocks/block'))}]"
        for m in left
    ]
    return rows, len(mutations)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("k", type=int, help="which part, 0-based")
    parser.add_argument("n", type=int, help="how many parts")
    parser.add_argument("--gradle", default="./gradlew", help="how to run Gradle")
    parser.add_argument("--lived", type=Path, help="where to write what lived")
    args = parser.parse_args(argv)

    lines = part(all_lines(), args.k, args.n)
    print(f"Part {args.k} of {args.n}: {len(lines)} lines, {lines[0]} to {lines[-1]}." if lines else f"Part {args.k} of {args.n} is empty.")
    rows, total = [], 0
    if lines:
        LINES.parent.mkdir(parents=True, exist_ok=True)
        LINES.write_text("\n".join(lines) + "\n")
        REPORT.unlink(missing_ok=True)
        subprocess.run([*shlex.split(args.gradle), ":app:pitestDebug", f"-PmutateLines={LINES}"], cwd=ROOT, check=True)
        rows, total = lived(REPORT)
    for row in rows:
        print(row)
    print(f"{total - len(rows)} of {total} changes caught by a test.")
    if args.lived:
        args.lived.write_text("".join(f"{row}\n" for row in rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
