#!/usr/bin/env python3
"""The second lock on the readings: every line of the audit read whole, by main's code and by this checkout's.

  python3 scripts/readingsaudit/readingsaudit.py lines                          # origin/main against HEAD
  python3 scripts/readingsaudit/readingsaudit.py lines --base REF               # against another commit
  python3 scripts/readingsaudit/readingsaudit.py lines --accept WORD KANA       # WORD read as KANA changes, on purpose

The golden rows hold a reading with a few letters of its line, so a rule that looks further along the line, or at
its length, gets past them. This reads each line of the audit whole, as the app reads it, with both dictionaries,
once with main's code and once with this checkout's. A reading main's code gets right that this one reads with
other kana, or splits otherwise, is a change, and the command exits 1. With none, it prints the line the pull
request's body needs: `golden: lines checked DIGEST`. The digest names what the readings are made of on both
sides, so check_golden.py asks for the command again when either side changes.

The lines are songs' words, so they stay in the audit folder, never in Git: CI can't run this, and a person runs
it before merging a pull request that changes how readings are made. main's code is checked out in a worktree of
its own in the agent cache, built in gradle-box, and removed after.

This file is part of the lock: check_golden.py asks a pull request that changes it to say so in its body.
"""
import contextlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

import check_golden
import golden
import readingsaudit as audit

TEST = "com.astrovm.crosstune.songcheck.WholeLines"
HARNESS = "app/src/test/java/com/astrovm/crosstune/songcheck/WholeLines.kt"
JOB = "lines-job.json"
ANSWER = "lines-answer.json"
DICTIONARIES = ("ipadic", "unidic")


def audit_lines(folder):
    """Each line the audit has UtaTen's ruby for, once for each ruby: [(text, truth)]."""
    seen, out = set(), []
    for line in golden.iter_audit_lines(folder):
        key = (line["text"], json.dumps(line["truth"], ensure_ascii=False))
        if key not in seen:
            seen.add(key)
            out.append((line["text"], line["truth"]))
    return out


def read_whole(folder, root, texts):
    """[texts] read whole by the checkout at [root], through WholeLines in gradle-box: {dictionary: {text: parts}}."""
    # The job holds the songs' lines, so it is in the audit folder, and goes once it is answered.
    job, answer = folder / JOB, folder / ANSWER
    answer.unlink(missing_ok=True)
    job.write_text(json.dumps(texts, ensure_ascii=False))
    try:
        if audit.songcheck(folder, TEST, root=root) != 0 or not answer.exists():
            raise SystemExit(f"WholeLines did not answer in {root}; its output is above")
        read = json.loads(answer.read_text())
    finally:
        job.unlink(missing_ok=True)
        answer.unlink(missing_ok=True)
    return {name: dict(zip(texts, read[name])) for name in DICTIONARIES}


@contextlib.contextmanager
def checkout(repo, commit, near):
    """[commit] in a worktree of its own in a new folder in [near], with this checkout's WholeLines when it has none.
    The worktree and its folder go after, whatever happens."""
    folder = Path(tempfile.mkdtemp(prefix="crosstune-lines-", dir=near))
    tree = folder / "base"
    try:
        subprocess.run(["git", "-C", str(repo), "worktree", "add", "--detach", "-q", str(tree), commit], check=True)
        try:
            if not (tree / HARNESS).exists():
                (tree / HARNESS).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(Path(repo) / HARNESS, tree / HARNESS)
            yield tree
        finally:
            subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(tree)], check=True)
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def changes(lines, before, after, accepted=()):
    """Each reading [before] gets right that [after] reads otherwise, as (word, kana, dictionary, line, ruby before,
    ruby after), less the (word, kana) pairs in [accepted]; the right readings in all; and the accepted ones that changed."""
    found, used, right = set(), set(), 0
    for text, truth in lines:
        units = audit.truth_units(text, truth)
        for name in DICTIONARIES:
            for word, kana, _wanted, ok, (start, end) in audit.compare_line(text, before[name][text], units):
                if not ok or not word:
                    continue
                right += 1
                was, now = golden.ruby_over(before[name][text], start, end), golden.ruby_over(after[name][text], start, end)
                if was == now:
                    continue
                if (word, kana) in accepted:
                    used.add((word, kana))
                else:
                    found.add((word, kana, name, text, was, now))
    return sorted(found), right, sorted(used)


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def lines(folder, accepted, base="origin/main", repo=audit.ROOT, read=read_whole):
    """The command: 0 and the body's line when no right reading changes, else 1 and each change."""
    paths = [*golden.READING_SOURCES, *golden.READING_BUILDS]
    if git(repo, "status", "--porcelain", "--", *paths):
        raise SystemExit("Commit the readings change first: the digest names commits, and this reads the checkout.")
    base_commit, head = git(repo, "rev-parse", "--verify", f"{base}^{{commit}}"), git(repo, "rev-parse", "HEAD")
    entries = audit_lines(folder)
    texts = sorted({text for text, _truth in entries})
    with checkout(repo, base_commit, folder.parent) as tree:
        before = read(folder, tree, texts)
    after = read(folder, repo, texts)
    found, right, used = changes(entries, before, after, {tuple(pair) for pair in accepted})
    print(f"{right} right readings on {len(texts)} whole lines, {base} ({base_commit[:8]}) against HEAD ({head[:8]}): {len(found)} change")
    for word, kana, name, text, was, now in found:
        print(f"  {word} {kana} {name}: {was} -> {now}  in {text}")
    if found:
        print("A reading that changes on purpose: run again with --accept WORD KANA, and put each in the body as golden: accept WORD KANA")
        return 1
    print("Put these lines in the pull request's body:")
    for word, kana in used:
        print(f"{check_golden.ACCEPT}{word} {kana}")
    print(f"{check_golden.LINES}{check_golden.readings_digest(repo, base_commit, head)}")
    return 0
