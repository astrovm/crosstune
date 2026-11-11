#!/usr/bin/env python3
"""A pull request keeps every locked reading: it removes or changes no golden row, and a readings change changes the file.

  python3 scripts/readingsaudit/check_golden.py --body-file BODY
  python3 scripts/readingsaudit/check_golden.py --repo . --base HEAD^1 --head HEAD --body-file BODY

The lint job runs it on a pull request: on the merge commit and the main commit before it (fetch-depth: 2),
with the pull request's body as GitHub has it when the job runs. A mark in the body is a line of its own:

  golden: none               the readings code changes, and no reading does
  golden: accept WORD KANA   the locked rows of WORD read as KANA go, on purpose

This reads only Git and the body, so it can be tested on a fake repository, with no GitHub.
"""
import argparse
import subprocess
import sys
from pathlib import Path

from readingsaudit import GOLDEN_REL, READING_BUILD, READING_BUILD_WORD, READING_SOURCES

ROOT = Path(__file__).resolve().parents[2]
NONE = "golden: none"
ACCEPT = "golden: accept "


def marks(body):
    """(whether the body says golden: none, the (word, kana) pairs it accepts). Only a whole line counts."""
    none, accepted = False, set()
    for line in body.splitlines():
        line = line.strip()
        if line == NONE:
            none = True
        elif line.startswith(ACCEPT):
            words = line[len(ACCEPT):].split()
            if len(words) == 2:
                accepted.add(tuple(words))
    return none, accepted


def rows_of(text):
    """The golden file's rows, as tuples, the header left out."""
    return [tuple(line.rstrip("\r").split("\t")) for line in text.split("\n") if line.rstrip("\r") and not line.startswith("#")]


def verdict(readings_changed, golden_changed, removed, body):
    """The reasons the pull request may not land. Empty when it may."""
    none, accepted = marks(body)
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
    return problems


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo, text=True)


def changed_files(repo, base, head):
    """Paths this range adds, edits, deletes or renames, old name and new name."""
    names = []
    for line in git(repo, "diff", "--name-status", base, head).splitlines():
        names.extend(line.split("\t")[1:])
    return names


def dictionaries_changed(repo, base, head):
    """Whether the build file's Kuromoji lines, the dictionaries and their version, change."""
    diff = git(repo, "diff", "-U0", base, head, "--", READING_BUILD)
    return any(line[:1] in "+-" and not line.startswith(("+++", "---")) and READING_BUILD_WORD in line for line in diff.splitlines())


def golden_at(repo, commit):
    """The golden file at [commit]; empty before there was one."""
    listed = git(repo, "ls-tree", "--name-only", commit, "--", GOLDEN_REL).strip()
    return git(repo, "show", f"{commit}:{GOLDEN_REL}") if listed else ""


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(ROOT), help="the repository to read")
    parser.add_argument("--base", default="HEAD^1", help="the commit the pull request merges into")
    parser.add_argument("--head", default="HEAD", help="the merge commit")
    parser.add_argument("--body-file", type=Path, help="the pull request's body, for its marks")
    args = parser.parse_args(argv)
    body = args.body_file.read_text(encoding="utf-8") if args.body_file else ""
    changed = set(changed_files(args.repo, args.base, args.head))
    readings_changed = bool(changed & set(READING_SOURCES)) or dictionaries_changed(args.repo, args.base, args.head)
    before, after = golden_at(args.repo, args.base), golden_at(args.repo, args.head)
    removed = set(rows_of(before)) - set(rows_of(after))
    problems = verdict(readings_changed, before != after, removed, body)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
