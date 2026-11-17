"""The inputs and readings proof used when propose.sh remakes the JMdict branch."""

import hashlib
import re
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
PROPOSAL_MARK = re.compile(r"^<!-- JMdict proposal ([0-9a-f]{40}) ([0-9a-f]{64}) -->$", re.MULTILINE)


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
    return f"<!-- JMdict proposal {head} {proof_inputs(repo, base, head)} -->"


def body_proof(body):
    shown = PROPOSAL_MARK.findall(body.replace("\r\n", "\n"))
    return shown[0] if len(shown) == 1 else None


def trailer(repo, head, name):
    return git(repo, "show", "-s", f"--format=%(trailers:key={name},valueonly)", head).strip()


def carried_marks(repo, base, head, legacy_context, body):
    """Keep the author's proof only for the same readings and the same golden lock."""
    none, accepted, _, checked, _ = marks(body)
    digest = readings_digest(repo, base, head)
    stamp = body_proof(body)
    context = stamp[1] if stamp else legacy_context
    if digest not in checked or context != proof_inputs(repo, base, head):
        return []
    return [
        *([NONE] if none else []),
        *(f"{ACCEPT}{word} {kana}" for word, kana in sorted(accepted)),
        f"{LINES}{digest}",
    ]


def verify(repo, base, head, tables, manifest, body):
    stamp = body_proof(body)
    if stamp is None:
        sys.exit("::error::The JMdict proposal body is stale. Run the refresh again before verifying readings.")
    snapshot, context = stamp
    if git(repo, "merge-base", snapshot, head).strip() != snapshot:
        sys.exit("::error::The JMdict proposal body is stale. Run the refresh again before verifying readings.")
    if not trailer(repo, head, INPUT_TRAILER) and snapshot != head:
        # A human commit may extend the snapshot with the golden file.
        edited = git(repo, "diff", "--name-only", snapshot, head, "--", ".", f":(exclude){GOLDEN_REL}")
        if edited:
            sys.exit("::error::The JMdict proposal changed outside the golden file. Refresh the snapshot before merging.")
    elif snapshot != head:
        sys.exit("::error::The JMdict proposal body is stale. Run the refresh again before verifying readings.")
    if trailer(repo, snapshot, INPUT_TRAILER) != snapshot_inputs(repo, base, tables, manifest):
        sys.exit("::error::JMdict inputs on main differ from the snapshot. Run the refresh again.")
    if context != proof_inputs(repo, base, snapshot):
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
