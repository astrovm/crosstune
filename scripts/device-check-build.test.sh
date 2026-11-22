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
# The stand-in writes each APK with the Gradle arguments in it, the app with the app's code and the runner with the
# runners' sources it was built from; it writes a file when FAKE_EDIT names one, and fails when FAKE_FAIL is set.
cat > "$repo/scripts/gradle-box/gradle-box.sh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
[[ -z ${FAKE_FAIL:-} ]] || exit 1
if [[ $1 == :app:assembleDebug ]]; then
    mkdir -p app/build/outputs/apk/debug
    { echo "app $*"; cat app/code.txt 2>/dev/null || true; } > app/build/outputs/apk/debug/app-debug.apk
else
    mkdir -p app/build/outputs/apk/androidTest/debug
    { echo "runner $*"; cat app/src/androidTest/java/com/astrovm/crosstune/*.kt 2>/dev/null || true; } > app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if [[ -n ${FAKE_EDIT:-} ]]; then echo edited > "$FAKE_EDIT"; fi
fi
FAKE
chmod +x "$repo/scripts/gradle-box/gradle-box.sh"
printf 'app/build/\nbuild/\n' > "$repo/.gitignore"
mkdir -p "$repo/app"
echo "code at the start" > "$repo/app/code.txt"
git -C "$repo" init -q
git -C "$repo" add -A
git -C "$repo" -c user.name=test -c user.email=test@example.invalid commit -qm "test: start"
head=$(git -C "$repo" rev-parse HEAD)
export CROSSTUNE_AGENT_CACHE=$work/cache
build() { "$repo/scripts/device-check-build.sh" "$@" > "$work/out" 2>&1; }

# A clean tree builds the runner it's named, into the folder it's named.
build PracticeClockCheck clockcheck || fail "a clean tree didn't build: $(cat "$work/out")"
grep -qx "runner :app:assembleDebugAndroidTest -PcrosstuneTestRunner=com.astrovm.crosstune.PracticeClockCheck" "$repo/build/clockcheck/app-debug-androidTest.apk" || fail "not the runner named"
[[ $(cat "$repo/build/clockcheck/head.txt") == "$head" ]] || fail "head.txt isn't the commit alone"
(cd "$repo/build/clockcheck" && sha256sum --check --strict --quiet sha256.txt) || fail "sha256.txt doesn't match the APKs"
grep -q '  app-debug.apk$' "$repo/build/clockcheck/sha256.txt" || fail "sha256.txt doesn't name the APKs bare"
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
build OfflineChordCheck chordcheck || fail "a clean tree didn't build: $(cat "$work/out")"
if FAKE_EDIT=$repo/late.txt build OfflineChordCheck chordcheck; then fail "a change during the build passed"; fi
grep -q "The source changed during the build" "$work/out" || fail "the late change isn't named"
[[ -z $(ls -A "$repo/build/chordcheck") ]] || fail "a build that failed left an earlier build's files: $(ls "$repo/build/chordcheck")"
FAKE_EDIT=$repo/late.txt build --dirty CaptureCheck capturecheck || fail "--dirty failed on a late change"
rm "$repo/late.txt"

# Wrong arguments say how to call it.
if build OfflineChordCheck; then fail "one argument passed"; fi
grep -q Usage "$work/out" || fail "no usage"
if build 'Offline;Check' chordcheck; then fail "a runner that isn't a class name passed"; fi
if build OfflineChordCheck ../out; then fail "a folder outside build/ passed"; fi

if build --at; then fail "--at with no commit passed"; fi
if build --later OfflineChordCheck chordcheck; then fail "an unknown option passed"; fi

# --at builds an older commit, such as main, with this checkout's newer runner in it, and says so.
runner=app/src/androidTest/java/com/astrovm/crosstune/RecordingNameCheck.kt
mkdir -p "$repo/$(dirname "$runner")"
echo "class RecordingNameCheck" > "$repo/$runner"
echo "code of the fix" > "$repo/app/code.txt"
git -C "$repo" add -A
git -C "$repo" -c user.name=test -c user.email=test@example.invalid commit -qm "test: the fix and its runner"
fix=$(git -C "$repo" rev-parse HEAD)
build --at "$head" RecordingNameCheck namescheck-main || fail "--at didn't build: $(cat "$work/out")"
grep -qx "code at the start" "$repo/build/namescheck-main/app-debug.apk" || fail "the app isn't the commit's own"
grep -qx "class RecordingNameCheck" "$repo/build/namescheck-main/app-debug-androidTest.apk" || fail "the runner isn't this checkout's"
[[ $(sed -n 1p "$repo/build/namescheck-main/head.txt") == "$head" ]] || fail "head.txt doesn't start with the commit built"
[[ $(sed -n 2p "$repo/build/namescheck-main/head.txt") == "with $runner from $fix, SHA-256 $(sha256sum "$repo/$runner" | cut -d' ' -f1)" ]] || fail "head.txt doesn't name the runner put in: $(cat "$repo/build/namescheck-main/head.txt")"
(cd "$repo/build/namescheck-main" && sha256sum --check --strict --quiet sha256.txt) || fail "--at's sha256.txt doesn't match the APKs"
[[ $(git -C "$repo" worktree list | wc -l) == 1 && -z $(ls -A "$work/cache") ]] || fail "--at left its checkout behind"
[[ -z $(git -C "$repo" status --porcelain) ]] || fail "--at changed this checkout"

# --at HEAD is a build here, as without it.
build --at HEAD RecordingNameCheck namescheck || fail "--at HEAD didn't build: $(cat "$work/out")"
[[ $(cat "$repo/build/namescheck/head.txt") == "$fix" ]] || fail "--at HEAD's head.txt isn't the commit alone"
grep -qx "code of the fix" "$repo/build/namescheck/app-debug.apk" || fail "--at HEAD isn't the fix"

# A failed build leaves no checkout either, nor the APKs an earlier build of that folder made from another commit.
if FAKE_FAIL=1 build --at "$head" RecordingNameCheck namescheck-main; then fail "a failed build passed"; fi
[[ $(git -C "$repo" worktree list | wc -l) == 1 && -z $(ls -A "$work/cache") ]] || fail "a failed --at left its checkout behind"
[[ -z $(ls -A "$repo/build/namescheck-main") ]] || fail "a failed --at left an earlier build's files: $(ls "$repo/build/namescheck-main")"

# A cache path with a $ or a quote in it is removed as it is, built or not.
odd="$work/odd \$name \"q"
for fails in "" 1; do
    want=yes
    [[ -z $fails ]] || want=no
    if CROSSTUNE_AGENT_CACHE=$odd FAKE_FAIL=$fails build --at "$head" RecordingNameCheck namescheck-main; then built=yes; else built=no; fi
    [[ $built == "$want" ]] || fail "--at with an odd cache path built: $built, not $want (fails=$fails): $(cat "$work/out")"
    [[ $(git -C "$repo" worktree list | wc -l) == 1 && -z $(ls -A "$odd") ]] || fail "--at left its checkout in an odd cache path (fails=$fails): $(ls -A "$odd")"
done

# An unknown commit, or a runner this checkout hasn't, builds nothing, and leaves nothing an earlier build made either.
build --at "$head" RecordingNameCheck namescheck-main || fail "--at didn't build again: $(cat "$work/out")"
if build --at nowhere RecordingNameCheck namescheck-main; then fail "an unknown commit built"; fi
grep -q "No commit nowhere" "$work/out" || fail "the unknown commit isn't named"
[[ -z $(ls -A "$repo/build/namescheck-main") ]] || fail "an unknown commit left an earlier build's files: $(ls "$repo/build/namescheck-main")"
if build --at "$head" OfflineChordCheck chordcheck-main; then fail "a runner that isn't here built"; fi
grep -q "No app/src/androidTest/java/com/astrovm/crosstune/OfflineChordCheck.kt" "$work/out" || fail "the missing runner isn't named"

echo "device-check-build.sh: all cases pass"
