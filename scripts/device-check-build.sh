#!/usr/bin/env bash
# Builds the dev app and one instrumentation runner from app/src/androidTest, each in its own Gradle box job, and keeps
# both APKs, the commit they're from (head.txt) and their SHA-256 (sha256.txt) in build/NAME/, for a check on a phone.
#
#   scripts/device-check-build.sh [--dirty] RUNNER NAME
#
# RUNNER is the runner's class in com.astrovm.crosstune, such as OfflineChordCheck, and NAME the folder, such as
# chordcheck. It refuses uncommitted changes, so the APKs are what the commit holds, and fails when the commit or the
# changes move during the build. --dirty is for a debugging probe, often built with a fix not yet committed: it builds
# anyway, lists what `git status` shows under the commit in head.txt, and fails only when the commit moves.
main() {
    set -euo pipefail
    local dirty=false
    if [[ ${1:-} == --dirty ]]; then
        dirty=true
        shift
    fi
    [[ $# == 2 ]] || { echo "Usage: scripts/device-check-build.sh [--dirty] RUNNER NAME" >&2; return 2; }
    local runner=$1 name=$2
    [[ $runner =~ ^[A-Za-z0-9_]+$ ]] || { echo "RUNNER is a class name, such as OfflineChordCheck" >&2; return 2; }
    [[ $name =~ ^[a-z0-9-]+$ ]] || { echo "NAME is a plain folder name, such as chordcheck" >&2; return 2; }
    cd "$(dirname "$0")/.."
    local head status
    head=$(git rev-parse HEAD)
    status=$(git status --porcelain)
    [[ $dirty == true || -z $status ]] || { echo "Commit the candidate before building it" >&2; return 1; }
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest "-PcrosstuneTestRunner=com.astrovm.crosstune.$runner"
    [[ $(git rev-parse HEAD) == "$head" ]] || { echo "HEAD moved during the build" >&2; return 1; }
    [[ $dirty == true || -z $(git status --porcelain) ]] || { echo "The source changed during the build" >&2; return 1; }
    local out=build/$name
    mkdir -p "$out"
    cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$out/"
    { echo "$head"; [[ $dirty == false ]] || git status --porcelain; } > "$out/head.txt"
    # sha256sum's lines; a Mac without it has shasum, which writes the same.
    if command -v sha256sum >/dev/null; then sha256sum "$out"/*.apk; else shasum -a 256 "$out"/*.apk; fi > "$out/sha256.txt"
    echo "Built $out/app-debug.apk and $out/app-debug-androidTest.apk from $head"
}
main "$@"; exit
