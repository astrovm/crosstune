#!/usr/bin/env python3
"""A change to the reading code has to change the locked readings, or say golden: none.

  python3 scripts/readingsaudit/check_golden.py
  python3 scripts/readingsaudit/check_golden.py --repo . --base HEAD^1 --head HEAD

The lint job has the pull request and the main commit it merges into (fetch-depth: 2).
This reads that diff, so it can be tested on a fake repository, with no GitHub.
"""
import argparse
import subprocess
import sys
from pathlib import Path

from readingsaudit import GOLDEN_REL, READING_SOURCES

ROOT = Path(__file__).resolve().parents[2]
MARK = "golden: none"


def verdict(changed, messages):
    """None when the diff may land. Otherwise the reason it may not."""
    changed = set(changed)
    if not changed & set(READING_SOURCES):
        return None
    if GOLDEN_REL in changed:
        return None
    # The mark can sit in any commit of the pull request, subject or body.
    if any(MARK in message for message in messages):
        return None
    return (
        "This changes Readings.kt or Furigana.kt and does not change the golden readings file.\n"
        "Update it with: python3 scripts/readingsaudit/readingsaudit.py golden\n"
        "When no reading changes, put golden: none in a commit message."
    )


def deepen_if_shallow(repo):
    """The lint job fetches two commits, so an older mark would be missing. Bring those commits back."""
    # A full clone already has them. A shallow one stops at the latest commit of the pull request.
    out = subprocess.check_output(["git", "rev-parse", "--is-shallow-repository"], cwd=repo, text=True).strip()
    if out != "true":
        return
    subprocess.check_call(["git", "fetch", "--no-tags", "--deepen=500"], cwd=repo)


def changed_files(repo, base, head):
    """Paths this range adds, edits, deletes or renames, old name and new name."""
    deepen_if_shallow(repo)
    out = subprocess.check_output(["git", "diff", "--name-status", base, head], cwd=repo, text=True)
    names = []
    for line in out.splitlines():
        parts = line.split("\t")
        names.extend(parts[1:])
    return names


def commit_messages(repo, base, head):
    """Each commit message in the range, whole, so a mark in the body counts."""
    out = subprocess.check_output(["git", "log", "--format=%B%x1e", f"{base}..{head}"], cwd=repo, text=True)
    return [part.strip("\n") for part in out.split("\x1e") if part.strip("\n")]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=str(ROOT), help="the repository to read")
    parser.add_argument("--base", default="HEAD^1", help="the commit the range starts after")
    parser.add_argument("--head", default="HEAD", help="the commit the range ends at")
    args = parser.parse_args(argv)
    problem = verdict(changed_files(args.repo, args.base, args.head), commit_messages(args.repo, args.base, args.head))
    if problem:
        print(problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
