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
# Uses ANDROID_SERIAL, or emulator-5554. Needs a Google APIs image (adb root), not a Play one.
set -euo pipefail

serial=${ANDROID_SERIAL:-emulator-5554}
shell() { timeout 20 adb -s "$serial" shell "$@"; }
demo() { shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

case ${1:-} in
setup)
    locale=${2:-es-AR}
    clock=${3:-01:46}
    [[ $clock =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || { echo "The clock goes as hh:mm, like 01:46, not $clock" >&2; exit 2; }
    adb -s "$serial" root >/dev/null
    adb -s "$serial" wait-for-device
    if [[ $(shell getprop persist.sys.locale | tr -d '\r') != "$locale" ]]; then
        shell setprop persist.sys.locale "$locale"
        shell setprop ctl.restart zygote
        sleep 5
        until [[ $(shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && shell dumpsys window | grep -q mCurrentFocus; do sleep 2; done
        sleep 5
    fi
    shell cmd uimode night yes >/dev/null
    shell settings put global auto_time 0
    shell date "$(date +%m%d)${clock/:/}$(date +%Y).00" >/dev/null
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
    shell settings put global auto_time 1
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
