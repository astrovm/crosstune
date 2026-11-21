#!/usr/bin/env bash
# Runs a build inside the box for gradle-box.sh, then says if it waited on memory, and if at the box's own limit.
#
#   in-box.sh COMMAND...
#
# A build that fills its box makes the kernel drop the jars' pages and read them in again, so a test can take
# minutes for one wait and time out (Issue #852). The machine can have memory to spare meanwhile, so
# gradle-box.sh's own check, of the whole machine, doesn't see it. The box's cgroup does: memory.pressure
# counts the time some task in it waited on memory, and memory.events how often it reached its limit and
# how many processes were killed for it. A wait with the box under its limit is the machine's shortage, or
# GRADLE_BOX_MEMORY_HIGH's slowing, so only a box that reached its limit is told to ask for more.
# GRADLE_BOX_CGROUP reads another folder, for the tests.
#
# Where Linux keeps no pressure (the pi's kernel, without psi=1), the waits read 0 and only a kill is told.
#
# The exit code is the command's. This runs as the box's first process, which ignores a stop it doesn't
# handle, so it hands a stop on to the build, one that came before the build started too.
main() {
set -uo pipefail

cgroup=${GRADLE_BOX_CGROUP:-/sys/fs/cgroup}
before=$(waited)
child=
stopped=
trap 'stopped=1; [[ -z $child ]] || kill -TERM "$child" 2>/dev/null || true' INT TERM HUP
"$@" &
child=$!
[[ -z $stopped ]] || kill -TERM "$child" 2>/dev/null || true
wait_for "$child"
lost=$((($(waited) - before) / 1000000))
full=$(event max)
killed=$(event oom_kill)
if ((killed > 0)); then
    echo "gradle-box: the box ran out of memory and $killed processes in it were killed. Ask for more with GRADLE_BOX_MEMORY (Issue #852)." >&2
elif ((lost >= 5 && full > 0)); then
    echo "gradle-box: this build waited on memory for $lost s, at the box's limit $full times. A test that timed out may have waited on that, not on the app. Ask for more with GRADLE_BOX_MEMORY (Issue #852)." >&2
elif ((lost >= 5)); then
    echo "gradle-box: this build waited on memory for $lost s, under the box's limit, so the machine was short of memory or GRADLE_BOX_MEMORY_HIGH slowed it. A test that timed out may have waited on that, not on the app (Issue #852)." >&2
fi
return "$status"

}

# Waits for background job $1 and puts its exit code in status. A stop ends a wait early, with 128 and the
# signal, so a job still running then is waited for again, for its own code.
wait_for() {
    while :; do
        status=0
        wait "$1" || status=$?
        if ((status <= 128)) || ! kill -0 "$1" 2>/dev/null; then
            break
        fi
    done
}

# The microseconds some task in the box waited on memory, or 0 where the cgroup doesn't say.
waited() {
    awk '$1 == "some" { for (i = 2; i <= NF; i++) if (index($i, "total=") == 1) print substr($i, 7) }' "$cgroup/memory.pressure" 2>/dev/null | grep . || echo 0
}

# How many times the box had the memory event $1, or 0 where the cgroup doesn't say.
event() {
    awk -v key="$1" '$1 == key { print $2 }' "$cgroup/memory.events" 2>/dev/null | grep . || echo 0
}

main "$@"; exit
