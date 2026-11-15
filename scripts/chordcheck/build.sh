#!/bin/bash
# Build the offline chord fixture APKs, separately, and record the exact inputs.

main() {
    set -euo pipefail
    cd "$(dirname "$0")/../.."
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebug
    nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.OfflineChordCheck
    mkdir -p build/chordcheck
    cp app/build/outputs/apk/debug/app-debug.apk build/chordcheck/
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/chordcheck/
    git rev-parse HEAD > build/chordcheck/head.txt
    python3 - <<'PY'
from hashlib import sha256
from pathlib import Path
root = Path('build/chordcheck')
(root / 'sha256.txt').write_text(''.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(root.glob('*.apk'))))
PY
}
main "$@"; exit
