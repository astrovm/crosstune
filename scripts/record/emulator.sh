#!/usr/bin/env bash
# Gets an emulator ready for screen recordings and screenshots, and back to normal after.
#
#   scripts/record/emulator.sh setup [locale] [hh:mm]   # defaults: es-AR, 01:46
#   scripts/record/emulator.sh restore
#
# setup: dark mode, a clean status bar (full battery and Wi-Fi, no notifications), the clock
# set to hh:mm so message times match it, and the locale. Changing the locale restarts
# Android's UI, about 20 s. Apps with their own language keep it: set one with
#   adb shell cmd locale set-app-locales <package> --locales es-AR
# restore also puts back the language and dark mode setup found.
# Uses ANDROID_SERIAL, or emulator-5580. Needs no root: made for crosstune-phone (scripts/emulator), and
# needs Android 17's `cmd locale`.
set -euo pipefail

serial=${ANDROID_SERIAL:-emulator-5580}
before=/data/local/tmp/record-before
shell() { timeout 20 adb -s "$serial" shell "$@"; }
demo() { shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

case ${1:-} in
setup)
    locale=${2:-es-AR}
    clock=${3:-01:46}
    [[ $clock =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || { echo "The clock goes as hh:mm, like 01:46, not $clock" >&2; exit 2; }
    found=$(shell cmd locale get-device-locale | tr -d '\r' || true)
    if ! [[ $found =~ ^[a-z]{2,3}(-[A-Za-z0-9]+)*$ ]]; then
        echo "This Android can't change its language without root. Use crosstune-phone: python3 scripts/emulator/emulator.py start crosstune-phone" >&2
        exit 1
    fi
    if [[ -z $(shell "cat $before 2>/dev/null || true" | tr -d '\r') ]]; then
        night=$(shell cmd uimode night | tr -d '\r')
        shell "echo $found ${night##* } > $before"
    fi
    shell cmd locale set-device-locale "$locale"
    sleep 5
    shell cmd uimode night yes >/dev/null
    shell settings put global auto_time 0
    # The clock is the device's own, so hh:mm counts from its midnight rather than this computer's.
    zone=$(shell date +%z | tr -d '\r')
    offset=$(( ${zone:0:1}1 * (10#${zone:1:2} * 3600 + 10#${zone:3:2} * 60) ))
    now=$(date +%s)
    shell cmd alarm set-time "$(( (now - (now + offset) % 86400 + 10#${clock:0:2} * 3600 + 10#${clock:3:2} * 60) * 1000 ))" >/dev/null
    shell settings put global sysui_demo_allowed 1
    demo enter
    demo clock --es hhmm "${clock/:/}"
    demo battery --es level 100 --es plugged false --es powersave false
    demo network --es wifi show --es level 4 --es fully true
    demo network --es mobile hide
    demo network --es airplane hide --es nosim hide
    demo status --es alarm_clock hide --es alarm hide --es sync hide --es volume hide --es bluetooth hide --es location hide
    demo notifications --es visible false
    echo "Ready: $locale, dark, $clock"
    ;;
restore)
    demo exit
    shell settings put global sysui_demo_allowed 0
    shell settings put global auto_time 1
    found=$(shell "cat $before 2>/dev/null || true" | tr -d '\r')
    if [[ -n $found ]]; then
        shell cmd locale set-device-locale "${found%% *}"
        shell cmd uimode night "${found##* }" >/dev/null
        shell rm -f "$before"
    fi
    for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
        shell settings put global "$scale" 1
    done
    shell settings put system show_touches 0
    echo "Back to normal"
    ;;
*)
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
    exit 2
    ;;
esac
