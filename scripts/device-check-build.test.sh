#!/usr/bin/env bash
# Runs device-check-build.sh in a throwaway repository with a stand-in gradle-box.sh that writes made-up APKs.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

repo=$work/repo
mkdir -p "$repo/scripts/gradle-box"
cp "$here/device-check-build.sh" "$repo/scripts/"
# The stand-in writes each APK with the Gradle arguments in it, and writes a file when FAKE_EDIT names one.
cat > "$repo/scripts/gradle-box/gradle-box.sh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
if [[ $1 == :app:assembleDebug ]]; then
    mkdir -p app/build/outputs/apk/debug
    echo "app $*" > app/build/outputs/apk/debug/app-debug.apk
else
    mkdir -p app/build/outputs/apk/androidTest/debug
    echo "runner $*" > app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if [[ -n ${FAKE_EDIT:-} ]]; then echo edited > "$FAKE_EDIT"; fi
fi
FAKE
chmod +x "$repo/scripts/gradle-box/gradle-box.sh"
printf 'app/build/\nbuild/\n' > "$repo/.gitignore"
git -C "$repo" init -q
git -C "$repo" add -A
git -C "$repo" -c user.name=test -c user.email=test@example.invalid commit -qm "test: start"
head=$(git -C "$repo" rev-parse HEAD)
build() { "$repo/scripts/device-check-build.sh" "$@" > "$work/out" 2>&1; }

# A clean tree builds the runner it's named, into the folder it's named.
build PracticeClockCheck clockcheck || fail "a clean tree didn't build: $(cat "$work/out")"
grep -qx "runner :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.PracticeClockCheck" "$repo/build/clockcheck/app-debug-androidTest.apk" || fail "not the runner named"
[[ $(cat "$repo/build/clockcheck/head.txt") == "$head" ]] || fail "head.txt isn't the commit alone"
(cd "$repo" && sha256sum --check --strict --quiet build/clockcheck/sha256.txt) || fail "sha256.txt doesn't match the APKs"
[[ $(wc -l < "$repo/build/clockcheck/sha256.txt") == 2 ]] || fail "sha256.txt isn't one line per APK"

# Uncommitted changes are refused before anything is built.
echo change > "$repo/notes.txt"
if build OfflineChordCheck chordcheck; then fail "a dirty tree built"; fi
grep -q "Commit the candidate" "$work/out" || fail "the refusal doesn't say why"
[[ ! -e $repo/build/chordcheck ]] || fail "a refused build wrote its folder"

# --dirty builds them, and lists the changes under the commit.
build --dirty CaptureCheck capturecheck || fail "--dirty didn't build: $(cat "$work/out")"
[[ $(cat "$repo/build/capturecheck/head.txt") == "$head"$'\n'"?? notes.txt" ]] || fail "head.txt doesn't list the changes"
rm "$repo/notes.txt"

# A change made while it builds fails a clean build, but not a --dirty one.
if FAKE_EDIT=$repo/late.txt build OfflineChordCheck chordcheck; then fail "a change during the build passed"; fi
grep -q "The source changed during the build" "$work/out" || fail "the late change isn't named"
FAKE_EDIT=$repo/late.txt build --dirty CaptureCheck capturecheck || fail "--dirty failed on a late change"
rm "$repo/late.txt"

# Wrong arguments say how to call it.
if build OfflineChordCheck; then fail "one argument passed"; fi
grep -q Usage "$work/out" || fail "no usage"
if build 'Offline;Check' chordcheck; then fail "a runner that isn't a class name passed"; fi
if build OfflineChordCheck ../out; then fail "a folder outside build/ passed"; fi

echo "device-check-build.sh: all cases pass"
