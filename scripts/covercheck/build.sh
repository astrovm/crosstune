#!/bin/bash
# Build the cover measurement APKs, separately, and record the exact inputs.

main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.CoverEncodingCheck
    mkdir -p build/covercheck
    cp app/build/outputs/apk/debug/app-debug.apk build/covercheck/
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/covercheck/
    git rev-parse HEAD > build/covercheck/head.txt
    python3 - <<'PY'
from hashlib import sha256
from pathlib import Path
root = Path('build/covercheck')
(root / 'sha256.txt').write_text(''.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(root.glob('*.apk'))))
PY
}
main "$@"; exit
