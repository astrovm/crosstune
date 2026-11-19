#!/bin/bash
# Build the public ChordWiki editor probe separately from the app.
main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.ChordWikiBrowserCheck
    mkdir -p build/wikicheck
    cp app/build/outputs/apk/debug/app-debug.apk build/wikicheck/
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/wikicheck/
    git rev-parse HEAD > build/wikicheck/head.txt
    sha256sum build/wikicheck/*.apk > build/wikicheck/sha256.txt
}
main "$@"; exit
