#!/usr/bin/env bash
# Builds the dev app and one instrumentation runner from app/src/androidTest, each in its own Gradle box job, and keeps
# both APKs, the commit they're from (head.txt) and their SHA-256 (sha256.txt) in build/NAME/, for a check on a phone.
#
#   scripts/device-check-build.sh [--dirty] [--at COMMIT] RUNNER NAME
#
# RUNNER is the runner's class in com.astrovm.crosstune, such as OfflineChordCheck, and NAME the folder, such as
# chordcheck. It refuses uncommitted changes, so the APKs are what the commit holds, and fails when the commit or the
# changes move during the build. --dirty is for a debugging probe, often built with a fix not yet committed: it builds
# anyway, lists what `git status` shows under the commit in head.txt, and fails only when the commit moves.
#
# --at builds another commit, such as main to compare a fix with, in a checkout of its own in the agent cache that's
# removed after. The runner may be newer than that commit, so this checkout's copy of it goes in, and head.txt says
# so, with its SHA-256 and the commit it's from. The app's code is the commit's alone. COMMIT the same as HEAD builds
# here, as without --at, which keeps the native build cache.
main() {
    set -euo pipefail
    local dirty=false at=
    while [[ ${1:-} == --* ]]; do
        case $1 in
            --dirty) dirty=true; shift ;;
            --at) [[ $# -ge 2 ]] || { echo "--at needs a COMMIT" >&2; return 2; }; at=$2; shift 2 ;;
            *) echo "Unknown option $1" >&2; return 2 ;;
        esac
    done
    [[ $# == 2 ]] || { echo "Usage: scripts/device-check-build.sh [--dirty] [--at COMMIT] RUNNER NAME" >&2; return 2; }
    local runner=$1 name=$2
    [[ $runner =~ ^[A-Za-z0-9_]+$ ]] || { echo "RUNNER is a class name, such as OfflineChordCheck" >&2; return 2; }
    [[ $name =~ ^[a-z0-9-]+$ ]] || { echo "NAME is a plain folder name, such as chordcheck" >&2; return 2; }
    cd "$(dirname "$0")/.."
    local here=$PWD head status sha tree source=app/src/androidTest/java/com/astrovm/crosstune/$runner.kt
    head=$(git rev-parse HEAD)
    status=$(git status --porcelain)
    [[ $dirty == true || -z $status ]] || { echo "Commit the candidate before building it" >&2; return 1; }
    sha=$head tree=$here
    if [[ -n $at ]]; then
        sha=$(git rev-parse --verify --quiet "$at^{commit}") || { echo "No commit $at" >&2; return 1; }
    fi
    if [[ $sha != "$head" ]]; then
        [[ -f $source ]] || { echo "No $source to build into $at" >&2; return 1; }
        # The agent cache, as devicelock.py finds it; CROSSTUNE_AGENT_CACHE points tests at a temp folder.
        local cache=${CROSSTUNE_AGENT_CACHE:-$HOME/.cache/agent-cache}
        [[ -n ${CROSSTUNE_AGENT_CACHE:-} || ! -d /mnt/data/agent-cache ]] || cache=/mnt/data/agent-cache
        mkdir -p "$cache"
        tree=$(mktemp -d "$cache/crosstune-device-check-$name.XXXXXX")
        # On any exit, so a failed build leaves no checkout behind.
        trap 'git -C "'"$here"'" worktree remove --force "'"$tree"'" 2>/dev/null || rm -rf "'"$tree"'"' EXIT
        git worktree add --detach "$tree" "$sha" >/dev/null
        git -C "$tree" submodule update --init --recursive
        mkdir -p "$tree/$(dirname "$source")"
        cp "$source" "$tree/$source"
    fi
    (
        cd "$tree"
        nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
        nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest "-PcrosstuneTestRunner=com.astrovm.crosstune.$runner"
    )
    [[ $(git rev-parse HEAD) == "$head" ]] || { echo "HEAD moved during the build" >&2; return 1; }
    [[ $(git -C "$tree" rev-parse HEAD) == "$sha" ]] || { echo "The commit built moved during the build" >&2; return 1; }
    [[ $dirty == true || -z $(git status --porcelain) ]] || { echo "The source changed during the build" >&2; return 1; }
    local out=build/$name
    mkdir -p "$out"
    cp "$tree/app/build/outputs/apk/debug/app-debug.apk" "$tree/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$out/"
    {
        echo "$sha"
        [[ $sha == "$head" ]] || echo "with $source from $head, SHA-256 $(sum256 "$source" | cut -d' ' -f1)"
        [[ $dirty == false ]] || git status --porcelain
    } > "$out/head.txt"
    # sha256sum's lines, with the bare names, so `sha256sum -c sha256.txt` works from inside the folder.
    local apks=(app-debug.apk app-debug-androidTest.apk)
    (cd "$out" && sum256 "${apks[@]}" > sha256.txt)
    echo "Built $out/app-debug.apk and $out/app-debug-androidTest.apk from $sha"
}

# sha256sum, or on a Mac without it shasum, which writes the same.
sum256() {
    if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}
main "$@"; exit
