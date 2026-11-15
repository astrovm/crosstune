#!/usr/bin/env bash
# Runs in the box for gradle-box.sh --also: ./gradlew in this checkout, then in each other one, with the same arguments.
#
#   each-checkout.sh COUNT DIR... GRADLE_ARGUMENTS...
#
# Every checkout runs whatever fails before it. The exit code is the first failure's.
set -uo pipefail

count=$1
shift
dirs=("$PWD" "${@:1:count}")
shift "$count"
status=0
for dir in "${dirs[@]}"; do
    (cd "$dir" && ./gradlew --no-daemon "$@")
    code=$?
    ((status != 0 || code == 0)) || status=$code
done
exit "$status"
