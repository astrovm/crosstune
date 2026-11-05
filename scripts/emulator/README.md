# Emulator with sound and hardware graphics

Lets more mic and playback tests run on an emulator instead of waiting for a phone.

Measured on Linux with an RX 6800, on Android 16 and 17 images.

## Start it

Nothing plays out loud: the emulator's speaker and mic both go through a private null sink.

```bash
AVD=crosstune-play    # any AVD
SINK=crosstune_quiet
SERIAL=emulator-5580
pactl list short sinks | grep -q "$SINK" || pactl load-module module-null-sink sink_name="$SINK"
PULSE_SINK=$SINK PULSE_SOURCE=$SINK.monitor \
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port 5580 -gpu host \
  -qt-hide-window -audio pa -allow-host-audio &
adb -s "$SERIAL" wait-for-device
```

1. `-gpu host`: draws on the real GPU. `-gpu auto` picks software here.
2. `-qt-hide-window`: no window, sound still works. `-no-window` runs a build that logs `Could not init 'pa' audio driver`.
3. T3's Device panel boots emulators with no sound and `-gpu auto`. To get both, start it here first. The panel attaches to it.

Same swipe test on the same AVD, from `dumpsys gfxinfo`:

| | Median frame | Janky frames |
|---|---|---|
| `-gpu host` | 16 to 20 ms | 0.15 to 2.9% |
| Software | 46 to 150 ms | 46 to 96% |

The machine's load wasn't the same across these runs, so read them as a size, not an exact ratio.

## Trust the mic only after this check

The mic works, but in 4 of 11 runs it got about 1 s of sound, then digital silence. Android still said it wasn't silenced, and sensor privacy was off. The cause isn't known yet.

So a bad song match doesn't prove the app is wrong, and it doesn't prove the mic is dead. Play a known tone first and look at the end of the capture:

```bash
ffmpeg -loglevel error -re -f lavfi -i sine=440:duration=9 -f pulse -device "$SINK" tone &
timeout 20 scrcpy -s "$SERIAL" --no-video --no-playback --no-window \
  --audio-source=mic --record=/tmp/mic.mka --time-limit=6
wait $!
ffmpeg -nostats -ss 4 -i /tmp/mic.mka -af volumedetect -f null - 2>&1 | grep max_volume
```

1. Around `-20 dB` or louder: the mic is live, go on.
2. `-91 dB`: it went silent. Run the check again, and restart the emulator if it stays silent.

Silence can start later, so check again right after a run you want to rely on.

Needs real speakers, a real room, or an account the emulator doesn't have? Use a phone.

## Trying Qobuz

`qobuz_try.py` is one try of a song link handed to Crosstune with Qobuz chosen, on an emulator with Qobuz logged in and the dev app installed: what Qobuz's media session plays, what Android started and blocked, by the second. A try that hits Qobuz's server errors says so, and doesn't count.

```bash
python3 scripts/emulator/qobuz_try.py emulator-5580 'https://www.deezer.com/track/2425603' 'Expresso Love' --pre other --path straight
```

`--path` is how it's opened: `straight` from the link, or `open` and `play` on the song's card, which tap the card's buttons where they are on a 1080 x 2400 screen. `--pre other` leaves Qobuz closed on another song first, as it can wake on that. It overwrites the dev app's settings.
