#!/usr/bin/env bash
# Compare this commit with origin/main on the same captured songs and candidate searches.
# Usage: GRADLE_BOX_DIR=/path/to/box scripts/matchcheck/compare.sh OUT
# This starts Gradle builds. Run it only when the machine is available for them.
set -euo pipefail
if [[ $# != 1 ]]; then echo "Usage: GRADLE_BOX_DIR=/path/to/box $0 OUT" >&2; exit 2; fi
: "${GRADLE_BOX_DIR:?Set GRADLE_BOX_DIR to the shared Gradle box}"
export GRADLE_BOX_DIR GRADLE_BOX_MEMORY="${GRADLE_BOX_MEMORY:-7}"
root=$(git rev-parse --show-toplevel)
out=$(cd "$1" && pwd)
git -C "$root" fetch origin
base=$(mktemp -d "${out%/*}/matcher-main.XXXXXXXX")
rmdir "$base"
git -C "$root" worktree add --detach "$base" origin/main
trap 'git -C "$root" worktree remove --force "$base"' EXIT
for file in app/src/test/java/com/astrovm/crosstune/MatchReplayTest.kt scripts/matchcheck/replay.py scripts/matchcheck/collect.py scripts/matchcheck/analyze.py scripts/gradle-box/gradle-box.sh; do
    cp "$root/$file" "$base/$file"
done
cd "$root"
python3 scripts/matchcheck/replay.py prepare "$out" --require-answers --also-latin
# The probe only records missing searches. Its match results are not used in the comparison.
python3 scripts/matchcheck/replay.py run "$out" probe --both --probe
python3 scripts/matchcheck/replay.py capture-proofs "$out"
(cd "$base" && python3 scripts/matchcheck/replay.py run "$out" main --both --require-proofs)
python3 scripts/matchcheck/replay.py run "$out" head --both --require-proofs
python3 scripts/matchcheck/replay.py table "$out" head-up --against main-up -v
python3 scripts/matchcheck/replay.py table "$out" head-down --against main-down -v
