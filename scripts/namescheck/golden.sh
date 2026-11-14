#!/usr/bin/env bash
# Writes app/src/test/resources/names-golden.tsv.gz: what main's SongNames made of every pair NamePairs.kt makes,
# so NamesGoldenTest can tell each change from main apart. Run it again after changing NamePairs.kt.
#
#   scripts/namescheck/golden.sh [SHA]     # SHA of main to read, 2c24d2d4 by default
#
# It checks SHA out in a worktree in the agent cache, copies NamePairs.kt and NamesGoldenWriter.kt into it, runs the
# writer there in the Gradle box, and removes the worktree. It needs git, gzip and what gradle-box.sh needs.
# It can't read a SHA whose SongNames has no names(title, artist, song) and wordsMatch(one, other).
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
sha=${1:-2c24d2d4}
for tool in git gzip; do command -v "$tool" >/dev/null || { echo "golden.sh needs $tool" >&2; exit 1; }; done
if [[ -d /mnt/data/agent-cache ]]; then cache=/mnt/data/agent-cache; else cache=$HOME/.cache/agent-cache; fi
tree=$cache/crosstune-namescheck-$sha
git -C "$here" worktree remove --force "$tree" 2>/dev/null || true
git -C "$here" worktree add --detach "$tree" "$sha" >/dev/null
trap 'git -C "$here" worktree remove --force "$tree"' EXIT
tests=$tree/app/src/test/java/com/astrovm/crosstune
cp "$here/app/src/test/java/com/astrovm/crosstune/NamePairs.kt" "$here/scripts/namescheck/NamesGoldenWriter.kt" "$tests/"
# The box of that checkout, which mounts it; only the writer runs, so main's own tests stay out of it.
(cd "$tree" && GRADLE_BOX_MEMORY=${GRADLE_BOX_MEMORY:-7} nice -n 10 scripts/gradle-box/gradle-box.sh --max-workers=2 :app:testDebugUnitTest --tests '*.NamesGoldenWriter')
gzip -9n < "$tree/app/build/names-golden.tsv" > "$here/app/src/test/resources/names-golden.tsv.gz"
echo "Wrote app/src/test/resources/names-golden.tsv.gz from $sha: $(gzip -dc "$here/app/src/test/resources/names-golden.tsv.gz" | wc -l) lines"
