# Emulators

Two, declared in [`emulators.json`](emulators.json), so a wiped machine gets them back with one command. Nothing else needs an emulator, and a phone one always has the Play Store.

| Emulator | Image | For | By hand |
|---|---|---|---|
| `crosstune-phone` | Android 17, Play Store | Music apps, Qobuz tries, sound and mic checks, promo recordings | Google test account, Qobuz and Amazon Music logins; Proton VPN and the Deezer sign-in still to do (Issue #678) |
| `crosstune-tv` | Google TV, Android 16, the newest TV image there is | TV screens, remote focus, Cast checks | Google test account |

```bash
python3 scripts/emulator/emulator.py create                         # both, safe to run twice
python3 scripts/emulator/emulator.py start crosstune-phone --window # to sign in, then:
python3 scripts/emulator/emulator.py apps crosstune-phone           # opens each missing app's Play page: tap Install
python3 scripts/emulator/emulator.py check                          # what's still missing
```

1. **Accounts:** this repo is public, so no address or password goes in it. `check` can tell whether a Google account is signed in, but not whether Qobuz is logged in, so it only reminds you.
2. **Not from the Play Store:** YouTube Music patched with Morphe is optional. Patch it on a computer or phone, then `adb install` it.
3. **On a Mac:** the same images in arm64. `start` runs with no sound there, as there's no private sink. On a machine with no SDK, like the pi, it says so.
4. **Adding one:** add it to `emulators.json`. A test fails if the app gains a music service this list doesn't have.

# Sound and hardware graphics

Lets more mic and playback tests run on an emulator instead of waiting for a phone.

Measured on Linux with an RX 6800, on Android 16 and 17 images.

## Start it

Nothing plays out loud: the emulator's speaker and mic both go through a private null sink.

```bash
python3 scripts/emulator/emulator.py start crosstune-phone   # then it's emulator-5580
```

1. `-gpu host`: draws on the real GPU. `-gpu auto` picks software here.
2. `-qt-hide-window`: no window, sound still works. `-no-window` runs a build that logs `Could not init 'pa' audio driver`.
3. `-no-snapshot`: always a cold boot (about a minute). Quick Boot loads the last saved snapshot, and that rolls the disk back to the day it was saved: every app and sign-in since is gone (this is how Proton VPN vanished on Nov 8). `start` also deletes an old `default_boot` snapshot, so nothing else that starts this emulator without flags can load it. If the emulator exits before it has booted, `start` fails and prints the end of `started.log`.
4. T3's Device panel boots emulators with no sound and `-gpu auto`. To get both, start it here first. The panel attaches to it.

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
SINK=crosstune_quiet
SERIAL=emulator-5580
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

`qobuz_compare.py` tries two dev builds in turn on one device, with `qobuz_try.py`, and gives the difference with its 95% interval. It stops at 40 pairs if the second build is no more than 5 points worse, or else goes on to 80. Run it again to pick up from `--out`. It installs each build, so it replaces the dev app, and puts the volume back when done. It also says, pair by pair with a 95% interval, how much later the second build starts the song and first launches Qobuz: an equal start rate can hide every song starting late.

```bash
python3 scripts/emulator/qobuz_compare.py SERIAL main.apk fix.apk 'https://www.deezer.com/track/2425603' 'Expresso Love' --out tries.jsonl
```

`--then LINK --then-want TITLE` asks for a newer song 3 seconds in, and a try counts only if the newer song plays and the older one doesn't come back.
