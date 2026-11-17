#!/usr/bin/env bash
# Build the dev app and the framework-only clock fixture separately from tests.
main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    [[ -z $(git status --porcelain) ]] || { echo "Commit the candidate before building it" >&2; return 1; }
    local head
    head=$(git rev-parse HEAD)
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.PracticeClockCheck
    [[ $(git rev-parse HEAD) == "$head" && -z $(git status --porcelain) ]] || { echo "The source changed during the build" >&2; return 1; }
    mkdir -p build/clockcheck
    cp app/build/outputs/apk/debug/app-debug.apk build/clockcheck/
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/clockcheck/
    git rev-parse HEAD > build/clockcheck/head.txt
    sha256sum build/clockcheck/*.apk > build/clockcheck/sha256.txt
}
main "$@"; exit
