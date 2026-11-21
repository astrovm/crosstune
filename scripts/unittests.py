#!/usr/bin/env python3
"""Runs the Python tests of every folder under scripts/, as CI's site job does.

  python3 scripts/unittests.py                          # every folder with a test_*.py in it
  python3 scripts/unittests.py scripts/perf scripts/lib # only these

Each folder runs in a process of its own, as `python3 -m unittest discover -s FOLDER -p "test_*.py"` from the
checkout, so its tests import the folder's own modules, and two folders' modules of the same name never meet. Every
folder runs, and then it names the ones that failed.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def folders() -> list[Path]:
    """Each folder under scripts/ with a test_*.py in it, from the checkout's root."""
    return sorted({test.parent.relative_to(ROOT) for test in (ROOT / "scripts").rglob("test_*.py")})


def main(named: list[str]) -> int:
    failed = []
    for folder in [Path(name).resolve() for name in named] or folders():
        print(f"== {folder}", flush=True)
        command = [sys.executable, "-m", "unittest", "discover", "-s", str(folder), "-p", "test_*.py"]
        if subprocess.run(command, cwd=ROOT, check=False).returncode:
            failed.append(str(folder))
    if failed:
        print("Failed: " + ", ".join(failed), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
