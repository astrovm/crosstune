#!/usr/bin/env python3
"""Makes main's readings of the fuzz titles, which TitlesAgainstMainTest holds the app's to.

    python3 scripts/titlefuzz/baseline.py [REF]

REF is the commit to read with, origin/main by default. It's checked out in a temporary worktree, under
$TITLEFUZZ_TMP if set; TitleFuzz.kt is copied in from this checkout, MainTitleProbes.kt as its TitleProbes.kt, and
WriteTitlesBaseline.kt runs in the Gradle box. The readings land in app/src/test/titlefuzz/main.tsv.gz, with the
commit on the first line. Make them again when TitleFuzz.kt changes what it makes, or when main's own readings
are to be the new floor.

It can't read a commit whose readers MainTitleProbes.kt doesn't reach: then change that file to match.
"""

import gzip
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
TESTS = Path("app/src/test/java/com/astrovm/crosstune")
OUT = ROOT / "app/src/test/titlefuzz/main.tsv.gz"


def git(*args, cwd=ROOT):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def main():
    ref = sys.argv[1] if len(sys.argv) > 1 else "origin/main"
    commit = git("rev-parse", ref)
    place = Path(tempfile.mkdtemp(prefix="crosstune-titlefuzz-", dir=os.environ.get("TITLEFUZZ_TMP")))
    checkout = place / "checkout"
    git("worktree", "add", "--detach", str(checkout), commit)
    try:
        shutil.copy(ROOT / TESTS / "TitleFuzz.kt", checkout / TESTS / "TitleFuzz.kt")
        shutil.copy(HERE / "MainTitleProbes.kt", checkout / TESTS / "TitleProbes.kt")
        shutil.copy(HERE / "WriteTitlesBaseline.kt", checkout / TESTS / "WriteTitlesBaseline.kt")
        env = dict(os.environ, GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
        subprocess.run(
            ["nice", "-n", "10", "scripts/gradle-box/gradle-box.sh", "--max-workers=2", ":app:testDebugUnitTest", "--tests", "*WriteTitlesBaseline"],
            cwd=checkout, env=env, check=True,
        )
        with gzip.open(checkout / "app/build/titles-baseline.tsv.gz", "rt", encoding="utf-8") as made:
            lines = made.read()
        OUT.parent.mkdir(parents=True, exist_ok=True)
        # No time in the file, so the same commit makes the same bytes.
        with open(OUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as out:
            out.write(f"# {commit}\n{lines}".encode("utf-8"))
        print(f"{OUT.relative_to(ROOT)}: {lines.count(chr(10))} titles read at {commit[:8]}, {OUT.stat().st_size} bytes")
    finally:
        git("worktree", "remove", "--force", str(checkout))
        shutil.rmtree(place, ignore_errors=True)


if __name__ == "__main__":
    main()
