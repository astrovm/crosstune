#!/bin/bash
# Build the cover measurement APKs, separately, and record the exact inputs.

main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assemblePlayDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assemblePlayDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.CoverEncodingCheck
    mkdir -p build/covercheck
    cp app/build/outputs/apk/play/debug/app-play-debug.apk build/covercheck/app-debug.apk
    cp app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk build/covercheck/app-debug-androidTest.apk
    git rev-parse HEAD > build/covercheck/head.txt
    python3 - <<'PY'
from hashlib import sha256
from pathlib import Path
root = Path('build/covercheck')
(root / 'sha256.txt').write_text(''.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(root.glob('*.apk'))))
PY
}
main "$@"; exit
