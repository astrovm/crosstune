#!/usr/bin/env python3
"""List every plain and timed lyric line changed between live LyricsFinder probe runs."""
import difflib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/live-text")
before = json.loads((root / "before.json").read_text())
after = json.loads((root / "after.json").read_text())
report = []
for song in before:
    a, b = before[song], after.get(song)
    report.append(f"## {song}")
    if not isinstance(a, dict) or not isinstance(b, dict):
        report.append(f"{a} -> {b}")
        continue
    report.append(f"Source: {a.get('source')} -> {b.get('source')}")
    for surface in ("words", "lines"):
        old = a[surface].splitlines() if surface == "words" else a[surface]
        new = b[surface].splitlines() if surface == "words" else b[surface]
        changes = list(difflib.unified_diff(old, new, fromfile=f"before {surface}", tofile=f"after {surface}", lineterm=""))
        report.extend(changes or [f"{surface}: unchanged"])
    report.append("")
(root / "changes.txt").write_text("\n".join(report) + "\n")
print("\n".join(report))
