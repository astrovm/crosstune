#!/usr/bin/env python3
"""A pull request keeps every locked reading: it removes or changes no golden row, and a readings change changes the file.

  python3 scripts/readingsaudit/check_golden.py --body-file BODY
  python3 scripts/readingsaudit/check_golden.py --repo . --base HEAD^1 --head HEAD --body-file BODY

The lint job runs it on a pull request: on the merge commit and the main commit before it (fetch-depth: 2),
with the pull request's body as GitHub has it when the job runs. It runs the checker the base commit has, with
the golden code next to it, so a pull request can't loosen the check by changing it. A mark in the body is a
line of its own:

  golden: none               the readings code changes, and no reading does
  golden: accept WORD KANA   the locked rows of WORD read as KANA go, on purpose
  golden: lock changed       the lock itself changes: this checker, the golden code, its tests, or where the file is

This reads only Git and the body, so it can be tested on a fake repository, with no GitHub.
"""
import argparse
import io
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

from golden import GOLDEN_REL, READING_BUILD, READING_BUILD_WORD, READING_SOURCES, readers_of

ROOT = Path(__file__).resolve().parents[2]
NONE = "golden: none"
ACCEPT = "golden: accept "
LOCK = "golden: lock changed"
# Where this checker and the golden code it imports live, as the base commit has them.
SCRIPTS = "scripts/readingsaudit"
CHECKER = f"{SCRIPTS}/check_golden.py"
# The lock's own files. A change to one could loosen the lock, so it has to be said.
LOCK_FILES = (
    CHECKER,
    f"{SCRIPTS}/golden.py",
    "app/src/test/java/com/astrovm/crosstune/GoldenReadings.kt",
    "app/src/test/java/com/astrovm/crosstune/ReadingsGoldenTest.kt",
    "app/src/test/java/com/astrovm/crosstune/songcheck/GoldenContexts.kt",
)


def marks(body):
    """(whether the body says golden: none, the (word, kana) pairs it accepts, whether it says golden: lock changed).
    Only a whole line counts."""
    none, accepted, lock = False, set(), False
    for line in body.splitlines():
        line = line.strip()
        if line == NONE:
            none = True
        elif line == LOCK:
            lock = True
        elif line.startswith(ACCEPT):
            words = line[len(ACCEPT):].split()
            if len(words) == 2:
                accepted.add(tuple(words))
    return none, accepted, lock


def rows_of(text):
    """The golden file's rows, as tuples, the header left out."""
    return [tuple(line.rstrip("\r").split("\t")) for line in text.split("\n") if line.rstrip("\r") and not line.startswith("#")]


def removed_rows(before, after):
    """The rows of [before] that [after] does not keep. A row is kept when [after] has its line, word, index, kana and
    ruby with the same dictionaries or more: a fix that makes the other dictionary read it right too keeps it."""
    kept = {}
    for row in after:
        if len(row) == 6:
            kept[row[:5]] = kept.get(row[:5], set()) | readers_of(row[5])
    return {row for row in before if row not in after and not (len(row) == 6 and readers_of(row[5]) <= kept.get(row[:5], set()))}


def verdict(readings_changed, golden_changed, removed, body, lock_changed=()):
    """The reasons the pull request may not land. Empty when it may."""
    none, accepted, lock = marks(body)
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


def dictionaries_changed(repo, base, head):
    """Whether the build file's Kuromoji lines, the dictionaries and their version, change."""
    diff = git(repo, "diff", "-U0", base, head, "--", READING_BUILD)
    return any(line[:1] in "+-" and not line.startswith(("+++", "---")) and READING_BUILD_WORD in line for line in diff.splitlines())


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


def golden_path(changed):
    """Where the golden file is after this range: its new name when it is moved."""
    return next((paths[1] for status, paths in changed if status == "R" and paths[0] == GOLDEN_REL), GOLDEN_REL)


def from_base(repo, base, argv):
    """Runs the checker [base] has, with the golden code next to it, on the same range and body. Its exit code, or
    None when [base] has no checker yet: the pull request that adds it is checked by its own."""
    if not git(repo, "ls-tree", "--name-only", base, "--", CHECKER).strip():
        return None
    archive = subprocess.run(["git", "archive", base, SCRIPTS], cwd=repo, check=True, capture_output=True).stdout
    with tempfile.TemporaryDirectory() as folder:
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(folder, filter="data")
        return subprocess.call([sys.executable, str(Path(folder) / CHECKER), "--own", "--repo", str(Path(repo).resolve()), *argv])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(ROOT), help="the repository to read")
    parser.add_argument("--base", default="HEAD^1", help="the commit the pull request merges into")
    parser.add_argument("--head", default="HEAD", help="the merge commit")
    parser.add_argument("--body-file", type=Path, help="the pull request's body, for its marks")
    parser.add_argument("--own", action="store_true", help="check with this checker, not the base commit's")
    args = parser.parse_args(argv)
    if not args.own:
        rest = ["--base", args.base, "--head", args.head] + (["--body-file", str(args.body_file.resolve())] if args.body_file else [])
        code = from_base(args.repo, args.base, rest)
        if code is not None:
            return code
    body = args.body_file.read_text(encoding="utf-8") if args.body_file else ""
    changed = changes(args.repo, args.base, args.head)
    names = {path for _status, paths in changed for path in paths}
    readings_changed = bool(names & set(READING_SOURCES)) or dictionaries_changed(args.repo, args.base, args.head)
    before = rows_of(file_at(args.repo, args.base, GOLDEN_REL))
    after = rows_of(file_at(args.repo, args.head, golden_path(changed)))
    # Only the rows count: a comment or the header changed alone is no new lock.
    problems = verdict(readings_changed, set(before) != set(after), removed_rows(set(before), set(after)), body, lock_changes(changed))
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
