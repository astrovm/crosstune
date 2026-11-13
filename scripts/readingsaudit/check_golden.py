#!/usr/bin/env python3
"""A pull request keeps every locked reading: it removes or changes no golden row, and a readings change changes the file.

  python3 scripts/readingsaudit/check_golden.py --body-file BODY
  python3 scripts/readingsaudit/check_golden.py --repo . --base HEAD^1 --head HEAD --body-file BODY

The lint job runs it on a pull request: on the merge commit and the main commit before it (fetch-depth: 2),
with the pull request's body as GitHub has it when the job runs. The job runs this file as main has it, copied
out of main's commit with the golden code next to it, so a pull request can't loosen the check by changing it.
A mark in the body is a line of its own:

  golden: none               the readings code changes, and no reading does
  golden: accept WORD KANA   the locked rows of WORD read as KANA go, on purpose
  golden: lock changed       the lock itself changes: this checker, the golden code, its tests, or where the file is
  golden: lines checked D    readingsaudit.py lines read every audit line whole with main's code and this one, and no
                             right reading changed; D names what the readings are made of on both sides

This reads only Git and the body, so it can be tested on a fake repository, with no GitHub.
"""
import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

from golden import (
    FIELDS,
    GOLDEN_REL,
    READING_BUILD_WORD,
    READING_BUILDS,
    READING_SOURCES,
    readers_of,
)

ROOT = Path(__file__).resolve().parents[2]
NONE = "golden: none"
ACCEPT = "golden: accept "
LOCK = "golden: lock changed"
LINES = "golden: lines checked "
# Where this checker and the golden code it imports live. The lint job copies this folder out of main's commit.
SCRIPTS = "scripts/readingsaudit"
CHECKER = f"{SCRIPTS}/check_golden.py"
# The lock's own files. A change to one could loosen the lock, so it has to be said.
LOCK_FILES = (
    CHECKER,
    f"{SCRIPTS}/golden.py",
    "app/src/test/java/com/astrovm/crosstune/GoldenReadings.kt",
    "app/src/test/java/com/astrovm/crosstune/ReadingsGoldenTest.kt",
    "app/src/test/java/com/astrovm/crosstune/songcheck/GoldenContexts.kt",
    f"{SCRIPTS}/wholelines.py",
    "app/src/test/java/com/astrovm/crosstune/songcheck/WholeLines.kt",
)


def marks(body):
    """(whether the body says golden: none, the (word, kana) pairs it accepts, whether it says golden: lock changed,
    the digests it says golden: lines checked for). Only a whole line counts."""
    none, accepted, lock, checked = False, set(), False, set()
    for line in body.splitlines():
        line = line.strip()
        if line == NONE:
            none = True
        elif line == LOCK:
            lock = True
        elif line.startswith(LINES):
            checked.add(line[len(LINES):].strip())
        elif line.startswith(ACCEPT):
            words = line[len(ACCEPT):].split()
            if len(words) == 2:
                accepted.add(tuple(words))
    return none, accepted, lock, checked


def rows_of(text):
    """The golden file's rows, as tuples, the header left out."""
    return [tuple(line.rstrip("\r").split("\t")) for line in text.split("\n") if line.rstrip("\r") and not line.startswith("#")]


def removed_rows(before, after):
    """The rows of [before] that [after] does not keep. A row is kept when [after] has all of it but the dictionary, the
    last field, with the same dictionaries or more: a fix that makes the other dictionary read it right too keeps it."""
    kept = {}
    for row in after:
        kept[row[:-1]] = kept.get(row[:-1], set()) | readers_of(row[-1])
    return {row for row in before
            if row not in after and not (len(row) == FIELDS and readers_of(row[-1]) <= kept.get(row[:-1], set()))}


def verdict(readings_changed, golden_changed, removed, body, lock_changed=(), digest=None):
    """The reasons the pull request may not land. Empty when it may. [digest] names what the readings are made of on
    both sides, when the pull request changes them."""
    none, accepted, lock, checked = marks(body)
    problems = []
    gone = sorted(row for row in removed if len(row) < 4 or (row[1], row[3]) not in accepted)
    if gone:
        problems.append(
            f"This removes or changes {len(gone)} locked readings. Each line is the word, its kana, the dictionary and the stretch:\n"
            + "".join(f"  {row[1] if len(row) > 1 else ''} {row[3] if len(row) > 3 else ''} {row[-1]} {row[0]}\n" for row in gone)
            + "A reading that changes on purpose needs a line in the pull request's body for each word and kana:\n"
            "golden: accept WORD KANA"
        )
    if readings_changed and not golden_changed and not none:
        problems.append(
            "This changes how readings are made and does not change the golden readings file.\n"
            "Update it with: python3 scripts/readingsaudit/readingsaudit.py golden\n"
            "When no reading changes, put a line in the pull request's body: golden: none"
        )
    if readings_changed and digest not in checked:
        problems.append(
            "This changes how readings are made, and the rows hold only a few letters of each line.\n"
            "Read every audit line whole with main's code and this one: python3 scripts/readingsaudit/readingsaudit.py lines\n"
            "When nothing it reads right changes, it prints a line for the pull request's body. That line is your word that you ran it."
        )
    if lock_changed and not lock:
        problems.append(
            "This changes the golden readings lock itself:\n"
            + "".join(f"  {path}\n" for path in sorted(lock_changed))
            + f"Say why in the pull request's body, with a line: {LOCK}"
        )
    return problems


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo, text=True)


def changes(repo, base, head):
    """What this range does to each path: (status letter, paths), the old name first for a rename."""
    out = []
    for line in git(repo, "diff", "--name-status", "-M", base, head).splitlines():
        status, *paths = line.split("\t")
        out.append((status[0], paths))
    return out


def kuromoji_lines(repo, commit):
    """The build's Kuromoji lines at [commit], the dictionaries and their version, in the build file and the version catalog."""
    return [line.strip() for path in READING_BUILDS for line in file_at(repo, commit, path).splitlines() if READING_BUILD_WORD in line]


def dictionaries_changed(repo, base, head):
    """Whether a Kuromoji line changes. The files are read whole and compared here, not with git diff, so no
    .gitattributes can hide a change from it, as `-diff` does."""
    return kuromoji_lines(repo, base) != kuromoji_lines(repo, head)


def file_at(repo, commit, path):
    """[path] at [commit]; empty when it is not there."""
    listed = git(repo, "ls-tree", "--name-only", commit, "--", path).strip()
    return git(repo, "show", f"{commit}:{path}") if listed else ""


def lock_changes(changed):
    """The lock's own files this range touches, and the golden file when it is moved or deleted."""
    touched = set()
    for status, paths in changed:
        touched |= {path for path in paths if path in LOCK_FILES}
        if paths[0] == GOLDEN_REL and status in "DR":
            touched.add(GOLDEN_REL)
    return touched


def readings_digest(repo, base, head):
    """What the readings are made of at [base] and at [head], in 16 hex digits: each source's blob, and the build's
    Kuromoji lines. `readingsaudit.py lines` prints it for what it read, and this works it out again, so the line in
    the body holds for that pair only: a new commit to the readings, here or on main, asks for the command again."""
    made = []
    for commit in (base, head):
        made.append(git(repo, "ls-tree", commit, "--", *READING_SOURCES))
        made += kuromoji_lines(repo, commit)
    return hashlib.sha256("\n".join(made).encode()).hexdigest()[:16]


def golden_path(changed):
    """Where the golden file is after this range: its new name when it is moved."""
    return next((paths[1] for status, paths in changed if status == "R" and paths[0] == GOLDEN_REL), GOLDEN_REL)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(ROOT), help="the repository to read")
    parser.add_argument("--base", default="HEAD^1", help="the commit the pull request merges into")
    parser.add_argument("--head", default="HEAD", help="the merge commit")
    parser.add_argument("--body-file", type=Path, help="the pull request's body, for its marks")
    args = parser.parse_args(argv)
    body = args.body_file.read_text(encoding="utf-8") if args.body_file else ""
    changed = changes(args.repo, args.base, args.head)
    names = {path for _status, paths in changed for path in paths}
    readings_changed = bool(names & set(READING_SOURCES)) or dictionaries_changed(args.repo, args.base, args.head)
    before = rows_of(file_at(args.repo, args.base, GOLDEN_REL))
    after = rows_of(file_at(args.repo, args.head, golden_path(changed)))
    # Only the rows count: a comment or the header changed alone is no new lock.
    digest = readings_digest(args.repo, args.base, args.head) if readings_changed else None
    problems = verdict(readings_changed, set(before) != set(after), removed_rows(set(before), set(after)), body, lock_changes(changed), digest)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
