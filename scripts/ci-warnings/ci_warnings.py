#!/usr/bin/env python3
"""Counts the warnings in GitHub Actions logs, per job and per kind.

A warning in a CI log is a bug here (Issue #812). This gives the same count every time, so a fix
can show the number before and after.

    python3 scripts/ci-warnings/ci_warnings.py 42904728554 42913224544   # these runs
    python3 scripts/ci-warnings/ci_warnings.py --branch main            # the newest finished run of each workflow
    python3 scripts/ci-warnings/ci_warnings.py --branch ci/x --lines    # and each warning line, grouped
    python3 scripts/ci-warnings/ci_warnings.py --log run.log            # a log saved with `gh run view ID --log`

It needs `gh`, logged in. Logs are kept in the agent cache (CI_WARNINGS_CACHE), since a run's log
never changes once it's done. `--fail` exits 1 when a warning is found.
"""

import argparse
import collections
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = os.environ.get("GH_REPO", "astrovm/crosstune")

# `gh run view --log` lines are "job<TAB>step<TAB>timestamp message".
LINE = re.compile(r"^(?P<job>[^\t]*)\t[^\t]*\t(?:﻿)?\d{4}-\d\d-\d\dT[\d:.]+Z ?(?P<msg>.*)$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")

# The runner echoes each step's script in cyan before it runs it. That's our script's text, not
# something it printed. Some logs keep the escape as the two characters "^[".
ECHO = re.compile(r"(\x1b|\^\[)\[36;1m")

# The first kind that matches wins, so the narrow ones come first.
KINDS = [
    ("gradle-deprecation", re.compile(r"Deprecated Gradle features were used in this build")),
    ("gradle-deprecation", re.compile(r"has been deprecated\. This is scheduled to be removed in Gradle")),
    ("kotlin", re.compile(r"^w: ")),
    ("pit-threads", re.compile(r"More threads at end of test")),
    ("pit-minion", re.compile(r"Minion exited abnormally due to")),
    ("git-hint", re.compile(r"^hint: Using '\w+' as the name for the initial branch")),
    ("actions", re.compile(r"^##\[warning\]")),
    ("node-deprecation", re.compile(r"\[DEP\d{4}\]|^\(node:\d+\) \w*Warning")),
    ("npm", re.compile(r"^npm (warn|WARN)\b")),
    ("python", re.compile(r"\.py:\d+: \w*Warning: ")),
    ("compiler", re.compile(r":\d+(:\d+)?: warning: |^warning: ")),
    ("lint", re.compile(r"^Lint found .*\d+ warnings?|: Warning: .*\[\w+\]$")),
    ("jvm", re.compile(r"^WARNING: ")),
    ("other", re.compile(r"(?i)\bwarn(ing)?s?\b|\bdeprecat")),
]

# Lines that say "warning" without being one: the Gradle 10 summary's second line, counts of
# zero, the names of options and of our own tests.
NOT_WARNINGS = re.compile(
    r"You can use '--warning-mode all'"
    r"|\b0 warnings?\b|\bno warnings\b|warnings? found: 0"
    r"|--warning-mode|warning\.mode|warningsAsErrors|allWarningsAsErrors|-Werror|-Wno-"
    r"|^\(Use `node --trace-deprecation"
    r"|^\[Incubating\] Problems report is available"
    r"|^hint: (?!Using '\w+' as the name for the initial branch)"
    r"|--warning=no-|^if-no-files-found:|Interpreted diagnostic query|^\s*\"(description|pattern)\" :"
)


def classify(message):
    """The kind of warning this log line is, or None."""
    if ECHO.search(message):
        return None
    text = ANSI.sub("", message).strip()
    if NOT_WARNINGS.search(text):
        return None
    for kind, pattern in KINDS:
        if pattern.search(text):
            return kind
    return None


def count(lines):
    """{(job, kind): [messages]} for the warning lines of one run's log."""
    found = collections.defaultdict(list)
    for raw in lines:
        match = LINE.match(raw.rstrip("\n"))
        if not match:
            continue
        kind = classify(match["msg"])
        if kind:
            found[(match["job"], kind)].append(ANSI.sub("", match["msg"]).strip())
    return found


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def cache_dir():
    default = "/mnt/data/agent-cache" if Path("/mnt/data/agent-cache").is_dir() else Path.home() / ".cache/agent-cache"
    path = Path(os.environ.get("CI_WARNINGS_CACHE", Path(default) / "crosstune-ci-warnings"))
    path.mkdir(parents=True, exist_ok=True)
    return path


def run_log(run_id):
    path = cache_dir() / f"{run_id}.log"
    if not path.exists():
        tmp = path.with_suffix(".part")
        with open(tmp, "w") as out:
            subprocess.run(["gh", "run", "view", str(run_id), "--repo", REPO, "--log"], check=True, stdout=out)
        tmp.rename(path)
    return path


def latest_runs(branch):
    """The newest finished run of each workflow on a branch, with its name."""
    runs = json.loads(gh("run", "list", "--repo", REPO, "--branch", branch, "--limit", "100",
                         "--json", "databaseId,workflowName,status,conclusion"))
    picked = {}
    for run in runs:
        if run["status"] != "completed" or run["conclusion"] in ("cancelled", "skipped"):
            continue
        picked.setdefault(run["workflowName"], run["databaseId"])
    return [(run_id, name) for name, run_id in picked.items()]


def report(name, found, lines, out):
    total = sum(len(v) for v in found.values())
    print(f"## {name}: {total}", file=out)
    for (job, kind), messages in sorted(found.items()):
        print(f"  {len(messages):6d}  {kind:20s} {job}", file=out)
        if lines:
            normal = collections.Counter(re.sub(r"\d+", "N", m)[:220] for m in messages)
            for text, n in normal.most_common(15):
                print(f"          {n:6d}x {text}", file=out)
    return total


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", nargs="*", help="run IDs")
    parser.add_argument("--branch", help="the newest finished run of each workflow on this branch")
    parser.add_argument("--log", action="append", default=[], help="a saved `gh run view --log` file")
    parser.add_argument("--lines", action="store_true", help="also print the warning lines, grouped")
    parser.add_argument("--fail", action="store_true", help="exit 1 when there is a warning")
    args = parser.parse_args(argv)

    targets = [(Path(p), p) for p in args.log]
    named = [(r, f"run {r}") for r in args.runs]
    if args.branch:
        named += [(r, f"{name} (run {r})") for r, name in latest_runs(args.branch)]
    targets += [(run_log(r), name) for r, name in named]
    if not targets:
        parser.error("give run IDs, --branch or --log")

    by_kind = collections.Counter()
    total = 0
    for path, name in targets:
        with open(path, encoding="utf-8", errors="replace") as log:
            found = count(log)
        total += report(name, found, args.lines, sys.stdout)
        for (_, kind), messages in found.items():
            by_kind[kind] += len(messages)
    print(f"## total: {total}")
    for kind, n in sorted(by_kind.items()):
        print(f"  {n:6d}  {kind}")
    return 1 if args.fail and total else 0


if __name__ == "__main__":
    sys.exit(main())
