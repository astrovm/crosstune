#!/usr/bin/env bash
# Records the emulator at a steady 60 fps.
#
#   scripts/record/record.sh out.mp4 <command> [args...]
#   scripts/record/record.sh --check video.mp4
#
# Runs <command> to drive the screen while recording, then writes out.mp4 and reports how
# smooth it is. The app draws 60 fps, but recording inside the emulator (adb screenrecord, or the
# emulator's own recorder) keeps only about 30, and scrcpy about 50. So while recording, Android's
# animations run at half speed, and out.mp4 plays the take back at double speed: every frame lands.
# Drive at half speed too: double your waits and swipe durations. For example:
#
#   scripts/record/record.sh playlist.mp4 sh -c '
#     adb shell input tap 400 1005; sleep 7
#     adb shell input swipe 540 1900 540 900 1800; sleep 4'
#
# Start the emulator with the host GPU and room to spare, or it drops frames before scrcpy sees them:
#   emulator -avd <name> -gpu host -cores 6 -memory 4096
# Needs scrcpy and ffmpeg. Uses ANDROID_SERIAL, or emulator-5554. MAX_SIZE sets the longer side
# of the recording (default 1280; bigger records fewer frames).
set -euo pipefail

serial=${ANDROID_SERIAL:-emulator-5554}
export ANDROID_SERIAL=$serial

check() {
    ffprobe -v error -show_entries format=duration -of csv=p=0 "$1" | xargs printf '%.1f s, '
    # Frames that change, and moving stretches where the next change took longer than a 60 fps frame.
    ffmpeg -hide_banner -i "$1" -vf "mpdecimate=hi=64*4:lo=64*2:frac=0.1,showinfo" -an -f null - 2>&1 |
        grep -o 'pts_time:[0-9.]*' |
        awk -F: 'NR > 1 { gap = ($2 - last) * 1000; if (gap < 400) { moving++; if (gap > 20) late++ } } { last = $2 }
                 END { printf "%d moving frames, %d late\n", moving, late }'
}

if [[ ${1:-} == --check ]]; then
    [[ -f ${2:-} ]] || { echo "No video at ${2:-}" >&2; exit 2; }
    check "$2"
    exit
fi
if [[ $# -lt 2 ]]; then
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
    exit 2
fi

out=$1
shift
take=$(mktemp --suffix=.mkv)
scales="window_animation_scale transition_animation_scale animator_duration_scale"
normal() {
    for scale in $scales; do adb shell settings put global "$scale" 1; done
    rm -f "$take"
}
trap normal EXIT

for scale in $scales; do adb shell settings put global "$scale" 2; done
scrcpy -s "$serial" --no-playback --no-window --no-audio --max-fps 60 --max-size "${MAX_SIZE:-1280}" \
    --video-bit-rate 20M --record "$take" --record-format mkv >/dev/null 2>&1 &
recorder=$!
sleep 1.5
"$@"
sleep 1
# Background jobs ignore Ctrl+C in scripts, so it stops on TERM, which scrcpy also saves on.
kill -TERM "$recorder"
wait "$recorder" || true

ffmpeg -v error -y -i "$take" -vf "setpts=0.5*PTS,fps=60" -an -c:v libx264 -crf 14 -preset slow -pix_fmt yuv420p "$out"
printf '%s: ' "$out"
check "$out"
