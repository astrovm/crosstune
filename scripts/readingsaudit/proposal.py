"""The inputs and readings proof used when propose.sh remakes the JMdict branch."""

import hashlib
import sys
from pathlib import Path

from check_golden import ACCEPT, LINES, LOCK_FILES, NONE, git, marks, readings_digest
from golden import GOLDEN_REL, READING_BUILDS, READING_SOURCES

# The build and the JVM test environment can change what the same source reads.
BUILD_INPUTS = (
    *READING_BUILDS,
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle",
    "gradlew",
    "app/src/main/AndroidManifest.xml",
    "app/src/debug/AndroidManifest.xml",
    "app/src/test/resources/robolectric.properties",
)
GENERATOR_INPUTS = (
    *READING_SOURCES,
    *BUILD_INPUTS,
    ".gitattributes",
    ".github/workflows/jmdict.yml",
    "scripts/readingsaudit",
    "scripts/notices.py",
    "scripts/kotlinc",
    "app/src/test/java/com/astrovm/crosstune/songcheck/JmdictReadings.kt",
)
PROOF_INPUTS = (
    *BUILD_INPUTS,
    *LOCK_FILES,
    GOLDEN_REL,
    ".gitattributes",
    "scripts/readingsaudit/readingsaudit.py",
    "scripts/gradle-box",
)


INPUT_TRAILER = "JMdict-Inputs"
PREVIOUS_PROOF = "JMdict-Previous-Proof"
MARKER_PREFIX = "<!-- JMdict proposal "


def tree_inputs(repo, commit, *paths):
    return git(repo, "ls-tree", commit, "--", *paths)


def snapshot_inputs(repo, commit, tables, manifest):
    notices = [f"{tables}/notices/{name}.txt" for name in ("edrdg-licence", "jmdict-documentation")]
    tree = tree_inputs(repo, commit, *GENERATOR_INPUTS, *notices, manifest)
    return hashlib.sha256(tree.encode()).hexdigest()


def proof_inputs(repo, base, head):
    trees = [tree_inputs(repo, commit, *PROOF_INPUTS) for commit in (base, head)]
    inputs = "\n".join([readings_digest(repo, base, head), *trees])
    return hashlib.sha256(inputs.encode()).hexdigest()


def marker(repo, base, head):
    return f"{MARKER_PREFIX}{head} {proof_inputs(repo, base, head)} -->"


def previous_proof_inputs(repo, head, body):
    # Use the proof inputs held by the body. The trailer covers a legacy body without a marker.
    shown = [line.removeprefix(MARKER_PREFIX).removesuffix(" -->").split()
             for line in body.splitlines() if line.startswith(MARKER_PREFIX)]
    if shown:
        return shown[0][1] if len(shown) == 1 and len(shown[0]) == 2 else ""
    return trailer(repo, head, PREVIOUS_PROOF)


def trailer(repo, head, name):
    return git(repo, "show", "-s", f"--format=%(trailers:key={name},valueonly)", head).strip()


def carried_marks(repo, base, head, body):
    """Keep the author's proof only for the same readings and the same golden lock."""
    none, accepted, _, checked, _ = marks(body)
    digest = readings_digest(repo, base, head)
    if digest not in checked or previous_proof_inputs(repo, head, body) != proof_inputs(repo, base, head):
        return []
    return [
        *([NONE] if none else []),
        *(f"{ACCEPT}{word} {kana}" for word, kana in sorted(accepted)),
        f"{LINES}{digest}",
    ]


def verify(repo, base, head, tables, manifest, body):
    if trailer(repo, head, INPUT_TRAILER) != snapshot_inputs(repo, base, tables, manifest):
        sys.exit("::error::JMdict inputs on main differ from the snapshot. Run the refresh again.")
    if marker(repo, base, head) not in body.splitlines():
        sys.exit("::error::The JMdict proposal body is stale. Run the refresh again before verifying readings.")


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    if command == "inputs":
        print(snapshot_inputs(".", *args))
    elif command == "context":
        print(proof_inputs(".", *args))
    elif command == "marker":
        print(marker(".", *args))
    elif command == "proof":
        for mark in carried_marks(".", *args, sys.stdin.read()):
            print(mark)
    elif command == "verify":
        verify(".", *args[:-1], Path(args[-1]).read_text(encoding="utf-8"))
    else:
        sys.exit(f"Unknown proposal command: {command}")
