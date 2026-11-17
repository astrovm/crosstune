"""The inputs and readings proof used when propose.sh remakes the JMdict branch."""

import sys

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


def carried_marks(repo, old_base, old_head, base, head, body):
    """Keep the author's proof only for the same readings and the same golden lock."""
    none, accepted, _, checked, _ = marks(body)
    if not checked:
        return []
    digest = readings_digest(repo, old_base, old_head)
    if digest not in checked or digest != readings_digest(repo, base, head):
        return []
    for before, after in ((old_base, base), (old_head, head)):
        if git(repo, "diff", "--name-only", before, after, "--", *PROOF_INPUTS):
            return []
    return [
        *([NONE] if none else []),
        *(f"{ACCEPT}{word} {kana}" for word, kana in sorted(accepted)),
        f"{LINES}{digest}",
    ]


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    if command == "inputs":
        print("\n".join(GENERATOR_INPUTS))
    elif command == "proof":
        for mark in carried_marks(".", *args, sys.stdin.read()):
            print(mark)
    else:
        sys.exit(f"Unknown proposal command: {command}")
