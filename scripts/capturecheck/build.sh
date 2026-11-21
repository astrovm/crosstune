#!/usr/bin/env bash
# Build the dev app and the CaptureCheck runner (app/src/androidTest/.../CaptureCheck.kt says how to run it),
# each in its own container job, and keep both APKs with the commit they're from in build/capturecheck/.
main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    local head
    head=$(git rev-parse HEAD)
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.CaptureCheck
    [[ $(git rev-parse HEAD) == "$head" ]] || { echo "HEAD moved during the build" >&2; return 1; }
    mkdir -p build/capturecheck
    cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/capturecheck/
    # A build with changes not yet committed says so beside the commit.
    { echo "$head"; git status --porcelain; } > build/capturecheck/head.txt
    echo "Install both from build/capturecheck/ with the SDK adb, then run the am instrument line in CaptureCheck.kt."
}
main "$@"; exit
