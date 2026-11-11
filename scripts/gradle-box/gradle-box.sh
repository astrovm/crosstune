#!/usr/bin/env bash
# Runs ./gradlew for this checkout in a container, in turn with every other checkout on this machine.
#
#   scripts/gradle-box/gradle-box.sh :app:testDebugUnitTest --tests '*LyricsFinderTest*'
#
# The container sees this checkout, the Android SDK read-only and the debug key, and not the rest
# of $HOME. Git comes from the image (eclipse-temurin:25-jdk plus Git), so whisper.cpp can read its
# commit on Linux and on a Mac. Builds wait in line for a slot, first come first served, and say how many
# are ahead: waiting is normal, not a hang. Each slot keeps its Gradle home, so a new worktree
# downloads nothing again.
#
# The first build to succeed keeps its downloads in seed/, which every later build reads, read-only,
# through Gradle's GRADLE_RO_DEP_CACHE, so a slot starting empty downloads only what's new since. To
# refresh it after many new dependencies: delete seed/ with no build running, and the next build that
# succeeds makes it again.
#
# GRADLE_BOX_MEMORY is each build's limit in GiB, 16 by default: :app:ci holds about 13 at its peak,
# between Gradle, the Kotlin compiler and, from 12 GiB, two test processes side by side, and at 8 or 12
# one of them gets killed. As many run at once as fit in three quarters of the RAM, and at least one.
# GRADLE_BOX_SLOTS can lower that, never raise it: every checkout shares the machine, so one asking for
# more slots than fit would run builds side by side that run it out of memory.
#
# A machine out of memory stalls a build so long that a test times out and looks broken (Issue #852).
# So a build waits to start while every task on the machine waited on memory for more than
# GRADLE_BOX_STALL_LIMIT percent (10 by default) of the last 10 s, and says when the machine stalled
# on memory during the build. Linux tells this in /proc/pressure/memory; a Mac doesn't, and builds as
# before. GRADLE_BOX_PRESSURE reads another file, for the tests.
#
# GRADLE_BOX_CPUS can lower the CPUs a build gets, never raise them, and GRADLE_BOX_MEMORY_HIGH, in
# GiB, slows a build down once it holds that much, as the kernel takes pages back. Both show how a test
# does on a busy machine, without making the machine busy. Neither changes the slots.
#
# On a Mac the box runs in podman's machine, so the memory and the CPUs it can have are that machine's,
# not the Mac's. A box asking for more than is there gets what is there, and is told. Nothing here needs
# GNU's tools or a newer bash than the Mac's own: locks are taken through python3, which is needed anyway.
#
# Idle slot homes are evicted oldest first above 10 GiB (GRADLE_BOX_CACHE_BYTES overrides it).
# Running slots and the shared seed stay intact, even if they alone exceed the target.
# A new seed is made only when the cache and its copy fit; its contents are then read-only.
# An older seed or a lowered limit can still leave the cache over target.
set -euo pipefail

here=$(dirname "${BASH_SOURCE[0]}")
containerfile=$here/Containerfile
# The tag is a hash of the Containerfile. A change builds a new image. A match reuses the old one.
image_id=$(python3 -c 'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' "$containerfile")
image=localhost/crosstune-gradle-box:$image_id
root=$(git -C "$here" rev-parse --show-toplevel)
common=$(cd "$root" && cd "$(git rev-parse --git-common-dir)" && pwd)
state=${GRADLE_BOX_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/crosstune-gradle-box}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
# Where Android Studio puts it on a Mac.
[[ -d $sdk || -n ${ANDROID_HOME:-} || ! -d $HOME/Library/Android/sdk ]] || sdk=$HOME/Library/Android/sdk
key=$HOME/.android/debug.keystore

if [[ ! -d $sdk ]]; then
    echo "gradle-box: no Android SDK at $sdk, set ANDROID_HOME" >&2
    exit 1
fi

# flock(1) isn't on a Mac. The lock is on the open file, so it stays with this shell, which keeps the
# file open, after python3 that took it is gone. "now" gives up when another holds it.
lock() {
    python3 -c 'import fcntl, sys
try:
    fcntl.flock(int(sys.argv[1]), fcntl.LOCK_EX | (fcntl.LOCK_NB if sys.argv[2:] else 0))
except BlockingIOError:
    sys.exit(1)' "$@"
}

# Off Linux, podman runs the box in a machine of its own, which has to be on, and the box can have
# no more memory or CPUs than it has.
meminfo=${GRADLE_BOX_MEMINFO:-/proc/meminfo}
machine=
if [[ ! -r $meminfo ]]; then
    if ! machine=$(podman info --format '{{.Host.MemTotal}} {{.Host.CPUs}}' 2>/dev/null) || [[ ! $machine =~ ^[0-9]+\ [0-9]+$ ]]; then
        echo "gradle-box: podman isn't answering. On a Mac, start its machine: podman machine start" >&2
        exit 1
    fi
fi

# Git is in the image. Hold the lock only for the build, so a Gradle slot stays free meanwhile.
ensure_box_image() {
    mkdir -p "$state"
    exec 8>"$state/image.lock"
    lock 8
    if ! nice -n 10 podman image exists "$image"; then
        echo "gradle-box: building $image" >&2
        local start
        start=$(date +%s)
        nice -n 10 podman build -t "$image" -f "$containerfile" "$(dirname "$containerfile")"
        echo "gradle-box: image built in $(( $(date +%s) - start ))s" >&2
    fi
    exec 8>&-
}
ensure_box_image

memory=${GRADLE_BOX_MEMORY:-16}
if [[ -z $machine ]]; then
    ram=$(awk '/^MemTotal/ { print int($2 / 1048576 + 0.5) }' "$meminfo")
    cores=$(nproc)
else
    ram=$(awk -v bytes="${machine% *}" 'BEGIN { print int(bytes / 1073741824 + 0.5) }')
    cores=${machine#* }
    # All of a small machine goes to the one build: it is there for nothing else.
    if ((memory > ram)); then
        echo "gradle-box: podman's machine has $ram GiB, so the build gets that, not $memory. A full build needs more: podman machine set --memory" >&2
        memory=$ram
    fi
    ((memory >= 1)) || memory=1
fi
fit=$((ram * 3 / 4 / memory))
slots=${GRADLE_BOX_SLOTS:-$fit}
((slots <= fit)) || slots=$fit
((slots >= 1)) || slots=1
cpus=$((cores / slots))
((cpus >= 1)) || cpus=1
# A fraction, like 0.5, is fine: podman takes it as a share of one CPU.
if [[ -n ${GRADLE_BOX_CPUS:-} ]] && awk -v want="$GRADLE_BOX_CPUS" -v have="$cpus" 'BEGIN { exit !(want > 0 && want < have) }'; then
    cpus=$GRADLE_BOX_CPUS
fi

# A ticket's name sorts by when it asked and ends in who asked, so one left by a killed build can go.
mkdir -p "$state/queue"
ticket=$state/queue/$(date +%s%N).$$
touch "$ticket"
trap 'rm -f "$ticket"' EXIT

ahead_of_me() {
    local other count=0
    for other in "$state"/queue/*; do
        [[ $other == "$ticket" || $other > "$ticket" ]] && continue
        if kill -0 "${other##*.}" 2>/dev/null; then
            count=$((count + 1))
        else
            rm -f "$other"
        fi
    done
    echo "$count"
}

pressure=${GRADLE_BOX_PRESSURE:-/proc/pressure/memory}
stall_limit=${GRADLE_BOX_STALL_LIMIT:-10}

# One number from the line where every task waited on memory: avg10, a share of the last 10 s in
# whole percent, or total, the microseconds since the machine started. 0 where Linux doesn't tell.
stalled() {
    [[ -r $pressure ]] || { echo 0; return; }
    # The total is printed as Linux writes it, since some awks print no number past 2^31 with %d.
    awk -v key="$1=" '$1 == "full" { for (i = 2; i <= NF; i++) if (index($i, key) == 1) { value = substr($i, length(key) + 1); print (key == "avg10=" ? int(value) : value); found = 1 } }
        END { if (!found) print 0 }' "$pressure"
}

take_a_slot() {
    local n
    for ((n = 1; n <= slots; n++)); do
        exec 9>"$state/slot-$n.lock"
        if lock 9 now; then
            slot=$n
            return 0
        fi
    done
    exec 9>&-
    return 1
}

said=
while :; do
    ahead=$(ahead_of_me)
    lately=$(stalled avg10)
    if ((ahead < slots && lately <= stall_limit)) && take_a_slot; then
        break
    fi
    if ((ahead >= slots)); then
        why="$ahead ahead of you"
    else
        why="the machine is out of memory, stalled $lately% of the last 10 s"
    fi
    # Said once for each change in who's ahead, and once for running out of memory.
    if [[ ${why%%,*} != "${said%%,*}" ]]; then
        echo "gradle-box: waiting, $why" >&2
    fi
    said=$why
    sleep 1
done

python3 "$(dirname "${BASH_SOURCE[0]}")/clean-cache.py" "$state" "$slot"
home=$state/gradle-$slot
seed=$state/seed
mkdir -p "$home"
# Gradle itself isn't a dependency, so the read-only cache doesn't cover it.
# A copy that shares the files' blocks where the disk can, with GNU's cp or the Mac's.
copy() {
    cp -a --reflink=auto "$1" "$2" 2>/dev/null || cp -a -c "$1" "$2" 2>/dev/null || cp -a "$1" "$2"
}
# What a folder takes on disk, in bytes.
bytes() {
    echo $(($(du -sk "$1" | cut -f1) * 1024))
}
if [[ -d $seed/wrapper && ! -d $home/wrapper ]]; then
    copy "$seed/wrapper" "$home/wrapper"
fi
box=(--rm --cpus "$cpus" --memory "${memory}g" --memory-swap "${memory}g"
    -v "$root:$root" -v "$sdk:$sdk:ro" -v "$home:$home"
    -w "$root" -e "ANDROID_HOME=$sdk" -e "GRADLE_USER_HOME=$home")
if [[ -n ${GRADLE_BOX_MEMORY_HIGH:-} ]]; then
    box+=(--cgroup-conf "memory.high=$(awk -v gib="$GRADLE_BOX_MEMORY_HIGH" 'BEGIN { printf "%d", gib * 1073741824 }')")
fi
# The replay's inputs can be kept apart from any checkout, for all of them (syncreplay.py).
if [[ -n ${SYNCREPLAY_DIR:-} ]]; then
    box+=(-v "$SYNCREPLAY_DIR:$SYNCREPLAY_DIR" -e "SYNCREPLAY_DIR=$SYNCREPLAY_DIR")
fi
# So are SongCheck's, as songs' words are people's and stay out of every checkout (scripts/readingsaudit).
if [[ -n ${SONGCHECK_DIR:-} ]]; then
    box+=(-v "$SONGCHECK_DIR:$SONGCHECK_DIR")
fi
# A worktree keeps its Git data in the main checkout, which may be somewhere else.
[[ $common == "$root"/* ]] || box+=(-v "$common:$common:ro")
# The same key as builds outside the box, so its APKs install over theirs.
[[ -f $key ]] && box+=(-v "$key:/root/.android/debug.keystore:ro")

if [[ -d $seed/modules-2 ]]; then
    touch "$seed"
    box+=(-v "$seed:$seed:ro" -e "GRADLE_RO_DEP_CACHE=$seed")
fi

# Made apart and moved in whole, so no build reads half of it. A build holds this slot until it's done.
keep_downloads() {
    [[ -d $home/caches/modules-2 && ! -e $seed ]] || return 0
    local limit=${GRADLE_BOX_CACHE_BYTES:-10737418240} used downloads
    used=$(bytes "$state")
    downloads=$(bytes "$home/caches/modules-2")
    [[ ! -d $home/wrapper ]] || downloads=$((downloads + $(bytes "$home/wrapper")))
    ((used + downloads <= limit)) || return 0
    local new=$state/seed.$$
    mkdir -p "$new"
    copy "$home/caches/modules-2" "$new/"
    [[ ! -d $home/wrapper ]] || copy "$home/wrapper" "$new/"
    # Gradle takes a directory with these in it for one in use, not a read-only copy.
    find "$new" \( -name '*.lock' -o -name gc.properties \) -delete
    # A rename, which fails when another build's seed is there already, where mv would move into it.
    python3 -c 'import os, sys; os.rename(sys.argv[1], sys.argv[2])' "$new" "$seed" 2>/dev/null || rm -rf "$new"
}

stalled_before=$(stalled total)
# A child rather than exec, so the ticket goes when it ends, and stopping this stops the build.
nice -n 10 podman run "${box[@]}" "$image" ./gradlew --no-daemon "$@" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null || true' INT TERM
status=0
while kill -0 "$child" 2>/dev/null; do
    wait "$child" || status=$?
done
((status != 0)) || keep_downloads
lost=$((($(stalled total) - stalled_before) / 1000000))
if ((lost >= 5)); then
    echo "gradle-box: the machine was out of memory for $lost s of this build. A test that timed out may have waited on that, not on the app (Issue #852)." >&2
fi
touch "$home"
exit "$status"
