#!/usr/bin/env bash
# Build an immutable app and the real-session probe, like golden.sh's temporary baseline checkout.
# Usage: device-build.sh COMMIT LABEL
main() {
    set -euo pipefail
    here=$(cd "$(dirname "$0")/../.." && pwd)
    sha=$(git -C "$here" rev-parse --verify "${1:?commit required}^{commit}")
    label=${2:?label required}
    [[ $label =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "Use a plain output label" >&2; return 1; }
    if [[ -d /mnt/data/agent-cache ]]; then cache=/mnt/data/agent-cache; else cache=$HOME/.cache/agent-cache; fi
    tree=$cache/crosstune-names-device-$label
    out=$here/build/names-device/$label
    [[ ! -e $tree ]] || { echo "The baseline checkout already exists: $tree" >&2; return 1; }
    if [[ $sha == "$(git -C "$here" rev-parse HEAD)" ]]; then
        [[ -z $(git -C "$here" status --porcelain) ]] || { echo "Commit the candidate before building it" >&2; return 1; }
        tree=$here
    else
        git -C "$here" worktree add --detach "$tree" "$sha" >/dev/null
        trap 'git -C "$here" worktree remove --force "$tree"' EXIT
        mkdir -p "$tree/app/src/androidTest/java/com/astrovm/crosstune"
        cp "$here/app/src/androidTest/java/com/astrovm/crosstune/RecordingNameCheck.kt" "$tree/app/src/androidTest/java/com/astrovm/crosstune/"
    fi
    mkdir -p "$out"
    rm -f "$out/ready.json"
    git -C "$tree" submodule update --init --recursive
    variant=""
    apk_dir=debug
    apk_name=app-debug
    if grep -Fq 'create("play")' "$tree/app/build.gradle.kts"; then
        variant=Play
        apk_dir=play/debug
        apk_name=app-play-debug
    fi
    (
        cd "$tree"
        nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh ":app:assemble${variant}Debug"
        nice -n 10 env GRADLE_BOX_MEMORY=7 scripts/gradle-box/gradle-box.sh ":app:assemble${variant}DebugAndroidTest" -PcrosstuneTestRunner=com.astrovm.crosstune.RecordingNameCheck
    )
    [[ $(git -C "$tree" rev-parse HEAD) == "$sha" ]] || { echo "The source commit changed during the build" >&2; return 1; }
    if [[ $tree == "$here" ]]; then
        [[ -z $(git -C "$here" status --porcelain) ]] || { echo "The candidate changed during the build" >&2; return 1; }
    fi
    cp "$tree/app/build/outputs/apk/$apk_dir/$apk_name.apk" "$out/app-debug.apk"
    cp "$tree/app/build/outputs/apk/androidTest/$apk_dir/$apk_name-androidTest.apk" "$out/app-debug-androidTest.apk"
    python3 - "$out" "$sha" "$tree" <<'PY'
from pathlib import Path
from hashlib import sha256
import json,sys
out=Path(sys.argv[1]);root=Path(sys.argv[3])
probe=root/'app/src/androidTest/java/com/astrovm/crosstune/RecordingNameCheck.kt'
(out/'ready.json').write_text(json.dumps({'app_commit':sys.argv[2],'probe_sha256':sha256(probe.read_bytes()).hexdigest(),'sha256':{p.name:sha256(p.read_bytes()).hexdigest() for p in sorted(out.glob('*.apk'))}},indent=2))
PY
}
main "$@"; exit
