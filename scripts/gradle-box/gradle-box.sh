#!/usr/bin/env bash
# Runs ./gradlew for this checkout in a container, in turn with every other checkout on this machine.
#
#   scripts/gradle-box/gradle-box.sh :app:testDebugUnitTest --tests '*LyricsFinderTest*'
#
# The container sees this checkout, the Android SDK read-only and the debug key, and not the rest of
# $HOME. Builds wait in line for a slot, first come first served, and say how many are ahead: waiting
# is normal, not a hang. Each slot keeps its Gradle home, so a new worktree downloads nothing again.
#
# The first build to succeed keeps its downloads in seed/, which every later build reads, read-only,
# through Gradle's GRADLE_RO_DEP_CACHE, so a slot starting empty downloads only what's new since. To
# refresh it after many new dependencies: delete seed/ with no build running, and the next build that
# succeeds makes it again.
#
# GRADLE_BOX_MEMORY is each build's limit in GiB, 8 by default. GRADLE_BOX_SLOTS is how many run at
# once, by default as many as fit in three quarters of the RAM.
set -euo pipefail

image=docker.io/library/eclipse-temurin:25-jdk
root=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
common=$(cd "$root" && cd "$(git rev-parse --git-common-dir)" && pwd)
state=${GRADLE_BOX_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/crosstune-gradle-box}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
key=$HOME/.android/debug.keystore

if [[ ! -d $sdk ]]; then
    echo "gradle-box: no Android SDK at $sdk, set ANDROID_HOME" >&2
    exit 1
fi

memory=${GRADLE_BOX_MEMORY:-8}
ram=$(awk '/^MemTotal/ { print int($2 / 1048576 + 0.5) }' /proc/meminfo)
slots=${GRADLE_BOX_SLOTS:-$((ram * 3 / 4 / memory))}
((slots >= 1)) || slots=1
cpus=$(($(nproc) / slots))
((cpus >= 1)) || cpus=1

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

take_a_slot() {
    local n
    for ((n = 1; n <= slots; n++)); do
        exec 9>"$state/slot-$n.lock"
        if flock -n 9; then
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
    if ((ahead < slots)) && take_a_slot; then
        break
    fi
    if [[ $ahead != "$said" ]]; then
        echo "gradle-box: waiting, $ahead ahead of you" >&2
        said=$ahead
    fi
    sleep 1
done

home=$state/gradle-$slot
seed=$state/seed
mkdir -p "$home"
# Gradle itself isn't a dependency, so the read-only cache doesn't cover it.
if [[ -d $seed/wrapper && ! -d $home/wrapper ]]; then
    cp -a --reflink=auto "$seed/wrapper" "$home/wrapper"
fi
box=(--rm --cpus "$cpus" --memory "${memory}g" --memory-swap "${memory}g"
    -v "$root:$root" -v "$sdk:$sdk:ro" -v "$home:$home"
    -w "$root" -e "ANDROID_HOME=$sdk" -e "GRADLE_USER_HOME=$home")
# A worktree keeps its Git data in the main checkout, which may be somewhere else.
[[ $common == "$root"/* ]] || box+=(-v "$common:$common:ro")
# The same key as builds outside the box, so its APKs install over theirs.
[[ -f $key ]] && box+=(-v "$key:/root/.android/debug.keystore:ro")

[[ -d $seed/modules-2 ]] && box+=(-v "$seed:$seed:ro" -e "GRADLE_RO_DEP_CACHE=$seed")

# Made apart and moved in whole, so no build reads half of it. A build holds this slot until it's done.
keep_downloads() {
    [[ -d $home/caches/modules-2 && ! -e $seed ]] || return 0
    local new=$state/seed.$$
    mkdir -p "$new"
    cp -a --reflink=auto "$home/caches/modules-2" "$new/"
    [[ ! -d $home/wrapper ]] || cp -a --reflink=auto "$home/wrapper" "$new/"
    # Gradle takes a directory with these in it for one in use, not a read-only copy.
    find "$new" \( -name '*.lock' -o -name gc.properties \) -delete
    mv -T "$new" "$seed" 2>/dev/null || rm -rf "$new"
}

# A child rather than exec, so the ticket goes when it ends, and stopping this stops the build.
nice -n 10 podman run "${box[@]}" "$image" ./gradlew --no-daemon "$@" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null || true' INT TERM
status=0
while kill -0 "$child" 2>/dev/null; do
    wait "$child" || status=$?
done
((status != 0)) || keep_downloads
exit "$status"
