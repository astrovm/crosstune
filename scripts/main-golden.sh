#!/usr/bin/env bash
# Writes what main's code made of every case a test's case list makes, so the golden test can tell each change from
# main apart. Run it again after changing the case list.
#
#   scripts/main-golden.sh names [SHA]   # names-golden.tsv.gz: what SongNames made of each pair NamePairs.kt makes,
#                                        # for NamesGoldenTest. SHA's SongNames needs names(title, artist, song) and
#                                        # wordsMatch(one, other).
#   scripts/main-golden.sh play [SHA]    # play-golden.tsv.gz: what MediaSessionPlayback offered as playing, and what
#                                        # each follow found, in every scene PlayScenes.kt makes, for PlayGoldenTest.
#                                        # SHA's MediaSessionPlayback needs whatPlays(), nowPlaying() and follow(song, app).
#
# SHA is the pinned commit of main to read (chosen by kind when omitted). It checks SHA out in a worktree in the agent cache, copies
# the case list and the writer (scripts/namescheck/NamesGoldenWriter.kt or scripts/playcheck/PlayGoldenWriter.kt) into
# it, runs the writer there in the Gradle box, writes app/src/test/resources/KIND-golden.tsv.gz and removes the
# worktree. It needs git, gzip and what gradle-box.sh needs.
# Parse the whole run before starting work, so an edit cannot change an active run.
main() {
    set -euo pipefail
    local kind=${1:-} cases writer folder
    case $kind in
        names) cases=NamePairs.kt writer=NamesGoldenWriter folder=namescheck ;;
        play) cases=PlayScenes.kt writer=PlayGoldenWriter folder=playcheck ;;
        *) echo "Usage: scripts/main-golden.sh names|play [SHA]" >&2; return 2 ;;
    esac
    # here and tree stay global: the trap that removes the worktree runs once main has returned.
    local sha cache tests golden
    here=$(cd "$(dirname "$0")/.." && pwd)
    if [[ $kind == play ]]; then
        sha=${2:-f69ce969be08a706f24c47aded14f714a6b07dd6}
    else
        sha=${2:-2c24d2d4}
    fi
    for tool in git gzip; do command -v "$tool" >/dev/null || { echo "main-golden.sh needs $tool" >&2; return 1; }; done
    if [[ -d /mnt/data/agent-cache ]]; then cache=/mnt/data/agent-cache; else cache=$HOME/.cache/agent-cache; fi
    tree=$cache/crosstune-$folder-$sha
    git -C "$here" worktree remove --force "$tree" 2>/dev/null || true
    git -C "$here" worktree add --detach "$tree" "$sha" >/dev/null
    trap 'git -C "$here" worktree remove --force "$tree"' EXIT
    tests=$tree/app/src/test/java/com/astrovm/crosstune
    cp "$here/app/src/test/java/com/astrovm/crosstune/$cases" "$here/scripts/$folder/$writer.kt" "$tests/"
    # The box of that checkout, which mounts it; only the writer runs, so main's own tests stay out of it.
    (cd "$tree" && GRADLE_BOX_MEMORY=${GRADLE_BOX_MEMORY:-7} nice -n 10 scripts/gradle-box/gradle-box.sh --max-workers=2 :app:testDebugUnitTest --tests "*.$writer")
    golden=app/src/test/resources/$kind-golden.tsv.gz
    gzip -9n < "$tree/app/build/$kind-golden.tsv" > "$here/$golden"
    echo "Wrote $golden from $sha: $(gzip -dc "$here/$golden" | wc -l) lines"
}

main "$@"; exit
