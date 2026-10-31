#!/usr/bin/env python3
"""Replays a real recording through the app's lyrics timing, in about half a minute, and says how
far from where each line is really sung the app showed it.

  python3 scripts/syncreplay/syncreplay.py prep ID LYRICS.json   # once per recording: see below
  python3 scripts/syncreplay/syncreplay.py run ID [--from MS]     # the app on it, through Gradle
  python3 scripts/syncreplay/syncreplay.py score ID [--from MS] [--placed]
  python3 scripts/syncreplay/syncreplay.py phone ID --seconds S [--again] [--app PACKAGE]

ID is a YouTube id. LYRICS.json is the timed words the app finds for it, as [[ms, "line"], ...],
e.g. from the app's lyrics.json cache. `prep` fetches the recording (yt-dlp), separates the voice
(demucs), finds when each line is really sung (faster-whisper medium on the voice, word by word),
and what whisper.cpp tiny, the app's own model, hears in each stretch of it, through the phone's
visualizer, as the phone would.
`run` plays it through the app (SyncReplayTest), from MS into the song: 0 is from the start, as the
app's clock is sure then; later is picked up partway. `--work MS` is how long the phone takes to
work out the words each time; try a few, as where each later stretch is heard moves with it. `--behind MS`
has the music app's clock that far behind what it plays, and `--behind-again MS` plays it a second
time, keeping what was learnt, to score that. `--gradle "podman exec box ./gradlew"` runs
Gradle some other way, like in a container. Everything goes in build/syncreplay/, never in git.
`phone` checks the same on a real phone over adb, with the dev app installed and the phone unlocked:
it plays the recording in YouTube Music (--app for another build of it) from the start, opens the
words in Crosstune for S seconds, and times each line lit from the app's own log. Where the song
really is comes from lining up what the app heard with the recording, as a music app's clock runs
behind what it plays, by more or less each time. It forgets what was learnt first, unless --again.
Wants ffmpeg, yt-dlp, cmake, and demucs, faster-whisper and numpy in the Python running it.
"""
import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "syncreplay"
HEARD = OUT / "heard"
MODEL = OUT / "ggml-tiny-q5_1.bin"
MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
# What the app hears at once, and at the start of a song, in ms.
LENGTHS = (20_000, 8_000)
STEP_MS = 1000
# Samples in each block a phone's mixer hands its visualizer at 48 kHz: 20 ms.
BLOCK = 960
ON_TIME_S = 0.5
# The app's logcat tag for this, off unless set to DEBUG (TIMING_LOG in SungWords.kt).
TIMING_LOG = "CrosstuneTiming"
DEV_APP = "com.astrovm.crosstune.dev"
# What the dev app learns of a song's timing, forgotten before each check unless --again.
LEARNT = ("lyrics_sync", "song_lengths", "sung_heard", "shazam_gaps")


def norm(word):
    return re.sub(r"[^a-z0-9']", "", unicodedata.normalize("NFKD", word.lower().replace("’", "'")))


def audio(path, kind="f32le"):
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", kind, "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32) if kind == "f32le" else np.frombuffer(raw, np.int16).astype(np.float32) / 32768


def heard_binary():
    if not (HEARD / "heard").exists():
        subprocess.run(["cmake", "-S", str(Path(__file__).parent), "-B", str(HEARD), "-DCMAKE_BUILD_TYPE=Release"], check=True)
        subprocess.run(["cmake", "--build", str(HEARD), "-j", "4"], check=True)
    if not MODEL.exists():
        subprocess.run(["curl", "-sSLo", str(MODEL), MODEL_URL], check=True)
    return HEARD / "heard"


def as_the_phone_hears(path):
    """The recording as the app gets it from Android's visualizer: each mixer block of BLOCK samples
    boosted by a power of two to fill 8 bits ("normalized" mode, so the sound pumps), then every 3
    samples at 48 kHz averaged into one at 16 kHz, as PlayedSound does. Whisper.cpp tiny mishears it
    the way the phone does, e.g. "Every time I see you call me" for "Every time I see you falling"."""
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "48000", "-f", "s16le", "-"], capture_output=True, check=True).stdout
    a = np.frombuffer(raw, np.int16).astype(np.int64)
    a = a[:len(a) // BLOCK * BLOCK].reshape(-1, BLOCK)
    shift = np.maximum(0, np.ceil(np.log2(np.maximum(np.abs(a).max(axis=1), 1) + 1)).astype(np.int64) - 8)
    byte = np.clip(a >> shift[:, None], -128, 127).ravel()
    byte = byte[:len(byte) // 3 * 3].reshape(-1, 3).mean(axis=1)
    return (byte * 256 / 32768).astype(np.float32)


def windows(song, wav, length):
    """What whisper.cpp tiny hears in each `length` ms from every STEP_MS, as the app hears it."""
    out = OUT / f"{song}.heard{length // 1000}.json"
    done = json.loads(out.read_text()) if out.exists() else {}
    a = as_the_phone_hears(wav)
    binary = heard_binary()
    starts = [s for s in range(0, len(a) // 16 - length, STEP_MS) if str(s) not in done]

    def hear(start):
        seg = a[start * 16:(start + length) * 16]
        text = subprocess.run([str(binary), str(MODEL), "en", ""], input=seg.tobytes(), capture_output=True).stdout.decode("utf-8", "replace")
        return start, [[int(ms), w] for ms, w in (x.split("\t", 1) for x in text.splitlines() if "\t" in x)]

    with ThreadPoolExecutor(1) as pool:
        for start, words in pool.map(hear, starts):
            done[str(start)] = words
    out.write_text(json.dumps(done))


def truth(song, wav, lines):
    """When each line with words is really sung: faster-whisper medium's word times on the separated voice,
    matched in order to the words. A line counts only when its first word and most of the rest match."""
    vocals = OUT / "htdemucs" / song / "vocals.wav"
    if not vocals.exists():
        subprocess.run([sys.executable, "-m", "demucs", "--two-stems=vocals", "-j", "1", "-o", str(OUT), str(wav)], check=True)
    from faster_whisper import WhisperModel
    model = WhisperModel("medium", device="cpu", compute_type="int8", cpu_threads=4)
    segments, _ = model.transcribe(audio(vocals), language="en", word_timestamps=True, condition_on_previous_text=False, beam_size=5)
    heard = [(norm(w.word), w.start) for s in segments for w in s.words if norm(w.word)]
    lyric = [(norm(w), i) for i, line in enumerate(lines) for w in line.split() if norm(w)]
    most = [[0] * (len(lyric) + 1) for _ in range(len(heard) + 1)]
    for i in range(len(heard) - 1, -1, -1):
        for j in range(len(lyric) - 1, -1, -1):
            most[i][j] = most[i + 1][j + 1] + 1 if heard[i][0] == lyric[j][0] else max(most[i + 1][j], most[i][j + 1])
    i = j = 0
    at = {}
    while i < len(heard) and j < len(lyric):
        if heard[i][0] == lyric[j][0]:
            at[j] = heard[i][1]
            i += 1
            j += 1
        elif most[i + 1][j] >= most[i][j + 1]:
            i += 1
        else:
            j += 1
    starts = {}
    for n, line in enumerate(lines):
        words = [j for j, (_, i) in enumerate(lyric) if i == n]
        if words and words[0] in at and sum(j in at for j in words) * 2 > len(words):
            starts[n] = at[words[0]]
    write_truth(song, lines, voiced(vocals, starts))


def voiced(vocals, starts):
    """Each line's start moved on to where the voice is, when it's in silence: whisper times the first
    word after a long rest early, by over a second (Tell It to My Heart's first three lines)."""
    import numpy as np
    a = audio(vocals)
    # How loud the voice is each 10 ms, over 50 ms; quiet is well under how loud it mostly sings.
    level = np.sqrt(np.convolve(a ** 2, np.ones(800) / 800, "same"))[::160]
    quiet = np.percentile(level[level > 0], 60) / 4
    moved = {}
    for n, start in starts.items():
        at = int(start * 100)
        on = next((i for i in range(at, min(at + 200, len(level))) if level[i] > quiet), at)
        moved[n] = on / 100
    return moved


def write_truth(song, lines, starts):
    with open(OUT / f"{song}.truth.jsonl", "w") as f:
        for n, line in enumerate(lines):
            if n in starts:
                f.write(json.dumps({"line": n, "text": line, "start": round(starts[n], 2)}) + "\n")


def prep(args):
    OUT.mkdir(parents=True, exist_ok=True)
    lyrics = json.loads(Path(args.lyrics).read_text())
    (OUT / f"{args.id}.lyrics.json").write_text(json.dumps(lyrics))
    wav = OUT / f"{args.id}.wav"
    if not wav.exists():
        subprocess.run(["yt-dlp", "-q", "-f", "bestaudio", "-x", "--audio-format", "wav", "-o", str(OUT / f"{args.id}.%(ext)s"), f"https://music.youtube.com/watch?v={args.id}"], check=True)
    truth(args.id, wav, [text for _, text in lyrics if text.strip()])
    for length in LENGTHS:
        windows(args.id, wav, length)


def run(args):
    # What SyncReplayTest plays, read from here, so with no replay asked for, it's skipped.
    asked = OUT / "replay.json"
    replay = {"song": args.id, "from": args.start, "work": args.work, "behind": args.behind}
    if args.behind_again is not None:
        replay["behindAgain"] = args.behind_again
    asked.write_text(json.dumps(replay))
    try:
        done = subprocess.run(shlex.split(args.gradle) + ["-q", ":app:testDebugUnitTest", "--tests", "*SyncReplayTest*", "--rerun"], cwd=ROOT, capture_output=True, text=True)
    finally:
        asked.unlink()
    if done.returncode:
        sys.exit(done.stdout[-3000:] + done.stderr[-3000:])
    score(args)


def score(args):
    shown = json.loads((OUT / f"{args.id}.from{args.start}.shown.json").read_text())
    sung = {(t := json.loads(l))["line"]: t["start"] for l in open(OUT / f"{args.id}.truth.jsonl")}
    # The app's line to the n-th line with words, and when each was first shown sung after the one before.
    nth = {}
    for i, text in enumerate(shown["lines"]):
        if text.strip():
            nth[i] = len(nth)
    first, after = {}, -1
    for e in shown["shown"]:
        k = nth.get(e["line"])
        if k is not None and k not in first and e["at"] > after:
            first[k] = e["at"] / 1000
            after = e["at"]
    lags = {k: first[k] - sung[k] for k in sung if k in first}
    for k in sorted(sung):
        text = shown["lines"][next(i for i in nth if nth[i] == k)][:34]
        print(f"{k:2d} sung {sung[k]:6.1f}  " + (f"shown {first[k]:6.1f} lag {lags[k]:+5.1f}  {text}" if k in lags else f"never shown   {text}"))
    if args.placed:
        for p in shown["placed"]:
            print(f"at {p['at'] / 1000:6.1f}: " + " ".join(f"{nth[i]}:{(p['times'][i] / 1000 - sung[nth[i]]):+.1f}" for i in nth if nth[i] in sung))
    # The lines sung once the words were first put right: what can be right as the song plays.
    fixed = shown["placed"][1]["at"] / 1000 if len(shown["placed"]) > 1 else 0
    live = [lag for k, lag in lags.items() if sung[k] > fixed]
    print(f"{args.id} from {args.start} ms: lines sung after the first fix at {fixed:.1f} s: "
          f"{sum(abs(x) <= ON_TIME_S for x in live)}/{len(live)} within {ON_TIME_S} s, median {statistics.median(abs(x) for x in live):.2f} s, "
          f"worst {max(live, key=abs):+.1f} s; never shown {sum(k not in first for k in sung)}")
    early = [lag for lag in lags.values() if lag < -ON_TIME_S]
    print(f"shown sung early, before its voice: {len(early)}/{len(lags)} lines" + (f", by up to {-min(early):.1f} s" if early else ""))


def adb(*command):
    tool = os.environ.get("ADB", str(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"))
    return subprocess.run([tool, *command], capture_output=True, check=True).stdout


def shell(*command):
    # The phone's shell reads the command again, so each part is quoted for it.
    return adb("shell", " ".join(shlex.quote(part) for part in command)).decode("utf-8", "replace")


def tap(text, tries=20):
    """Taps the first thing on screen showing [text], or saying it to TalkBack; whether there was one."""
    for _ in range(tries):
        # The screen can't always be read, as while it moves: it's read again.
        try:
            shell("uiautomator", "dump", "/sdcard/window.xml")
            screen = shell("cat", "/sdcard/window.xml")
        except subprocess.CalledProcessError:
            screen = ""
        for node in re.findall(r"<node [^>]*>", screen):
            if f'text="{text}"' in node or f'content-desc="{text}"' in node:
                x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        time.sleep(0.5)
    return False


def playing(app):
    """The song [app] plays, its title, and when by the phone's clock it was at 0 ms; None till it plays."""
    session = shell("dumpsys", "media_session").split(f"package={app}")
    if len(session) < 2:
        return None
    state = re.search(r"state=PLAYING\(3\), position=(\d+),.*?updated=(\d+)", session[1][:3000])
    title = re.search(r"description=([^,]*),", session[1][:3000])
    return (title.group(1), int(state.group(2)) - int(state.group(1))) if state and title else None


def phone(args):
    shell("setprop", f"log.tag.{TIMING_LOG}", "DEBUG")
    # Started again, Crosstune reads the log level, and nothing's left from before.
    shell("am", "force-stop", DEV_APP)
    shell("am", "force-stop", args.app)
    shell("run-as", DEV_APP, "find", "cache", "-name", "heard-*.pcm", "-delete")
    if not args.again:
        for key in LEARNT:
            shell("run-as", DEV_APP, "sed", "-i", "-e", f"s#<string name=\"{key}\">[^<]*</string>##", "shared_prefs/crosstune_preferences.xml")
        shell("run-as", DEV_APP, "rm", "-f", "cache/kept_words.json", "cache/lyrics.json", "cache/sung_readings.json", "cache/lookups.json")
    adb("logcat", "-G", "16M")
    adb("logcat", "-c")
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)
    # A lock screen without a PIN goes with a swipe up; one with a PIN needs the phone unlocked first.
    if "NotificationShade" in shell("dumpsys", "window", "windows").split("mCurrentFocus", 1)[-1][:200]:
        width, height = map(int, re.search(r"(\d+)x(\d+)", shell("wm", "size")).groups())
        shell("input", "swipe", str(width // 2), str(height * 9 // 10), str(width // 2), str(height * 3 // 10), "200")
    shell("am", "start", "-a", "android.intent.action.VIEW", "-d", f"https://music.youtube.com/watch?v={args.id}", "-p", args.app)
    for _ in range(60):
        song = playing(args.app)
        if song:
            break
        time.sleep(0.5)
    else:
        sys.exit(f"{args.app} didn't play {args.id}")
    title, zero = song
    # Picked up as it plays, as when Crosstune is opened on a song already playing.
    shell("monkey", "-p", DEV_APP, "-c", "android.intent.category.LAUNCHER", "1")
    if not (tap(title) and tap("Lyrics")):
        sys.exit("Couldn't open the words in Crosstune: is the phone unlocked?")
    while float(shell("cat", "/proc/uptime").split()[0]) * 1000 - zero < args.seconds * 1000:
        # The words are only followed while they're in sight, so the screen is kept from sleeping.
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(5)
    shell("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
    log = adb("logcat", "-d", "-s", f"{TIMING_LOG}:D").decode()
    kept = OUT / "phone" / args.id
    kept.mkdir(parents=True, exist_ok=True)
    for old in kept.glob("heard-*.pcm"):
        old.unlink()
    for name in shell("run-as", DEV_APP, "ls", "cache").split():
        if name.startswith("heard-"):
            (kept / name).write_bytes(adb("exec-out", "run-as", DEV_APP, "cat", f"cache/{name}"))
    (kept / "log.txt").write_text(log)
    shell("run-as", DEV_APP, "find", "cache", "-name", "heard-*.pcm", "-delete")
    score_phone(args.id, kept)


def behind(song, kept, lit):
    """How far behind what's heard the app's clock is: each while kept, found in the recording, against where the app said the song was."""
    import numpy as np
    offs = []
    for f in sorted(kept.glob("heard-*.pcm")):
        started = int(re.search(r"heard-(\d+)", f.name).group(1))
        clock = min(lit, key=lambda e: abs(e[1] - started))
        app = clock[2] + started - clock[1]
        heard = np.frombuffer(f.read_bytes(), np.int16).astype(np.float32)[:16000 * 8]
        n = 1 << int(np.ceil(np.log2(len(song) + len(heard))))
        match = np.fft.irfft(np.fft.rfft(song, n) * np.conj(np.fft.rfft(heard, n)), n)[:len(song) - len(heard)]
        loud = np.sqrt(np.convolve(song ** 2, np.ones(len(heard)), "valid")[:len(match)]) + 1e-9
        at = int(np.argmax(match / loud)) / 16
        offs.append(at - app)
        print(f"heard from {at / 1000:6.1f} s, the app said {app / 1000:6.1f} s: {(at - app) / 1000:+.2f} s behind")
    return statistics.median(offs)


def score_phone(song_id, kept):
    lit = [(int(i), int(at), int(pos)) for i, at, pos in re.findall(r"lit (-?\d+) at (\d+) pos (-?\d+)", (kept / "log.txt").read_text())]
    if not lit:
        sys.exit("No lines lit in the log: was the dev app reinstalled after the log was added, and the words open?")
    lag = behind(audio(OUT / f"{song_id}.wav", "s16le") * 32768, kept, lit)
    sung = {(t := json.loads(l))["line"]: t["start"] for l in open(OUT / f"{song_id}.truth.jsonl")}
    # The app's lines are the found words', as in LYRICS.json given to prep; truth counts only those with words.
    words = [line for _, line in json.loads((OUT / f"{song_id}.lyrics.json").read_text())]
    nth = {i: k for k, i in enumerate(i for i, line in enumerate(words) if line.strip())}
    first = {}
    for i, _, pos in lit:
        if nth.get(i) is not None and nth[i] not in first:
            first[nth[i]] = (pos + lag) / 1000
    lags = {k: first[k] - sung[k] for k in sung if k in first}
    for k in sorted(sung):
        text = words[next(i for i in nth if nth[i] == k)][:34]
        print(f"{k:2d} sung {sung[k]:6.1f}  " + (f"lit {first[k]:6.1f} lag {lags[k]:+5.1f}  {text}" if k in lags else f"never lit   {text}"))
    print(f"{song_id} on the phone, the app's clock {lag / 1000:.2f} s behind: {sum(abs(x) <= ON_TIME_S for x in lags.values())}/{len(lags)} within {ON_TIME_S} s, "
          f"{sum(abs(x) <= 1 for x in lags.values())}/{len(lags)} within 1 s, median {statistics.median(abs(x) for x in lags.values()):.2f} s; never lit {sum(k not in first for k in sung)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    steps = parser.add_subparsers(dest="step", required=True)
    p = steps.add_parser("prep")
    p.add_argument("id")
    p.add_argument("lyrics")
    p.set_defaults(go=prep)
    for name, go in (("run", run), ("score", score)):
        p = steps.add_parser(name)
        p.add_argument("id")
        p.add_argument("--from", dest="start", type=int, default=0)
        p.add_argument("--placed", action="store_true", help="also how far off each line was each time the lines moved")
        p.set_defaults(go=go)
        if name == "run":
            p.add_argument("--gradle", default="./gradlew --max-workers=4")
            # The phone takes about this long, but it varies, and where each later stretch is heard with it.
            p.add_argument("--work", type=int, default=2500, help="ms the phone takes to work out what it heard")
            # YouTube Music's was 450 to 880 ms behind, each time a little different.
            p.add_argument("--behind", type=int, default=0, help="ms the music app's clock is behind what it plays")
            p.add_argument("--behind-again", type=int, help="play it a second time, keeping what was learnt, with the clock this far behind; scored is the second")
    p = steps.add_parser("phone")
    p.add_argument("id")
    p.add_argument("--seconds", type=int, required=True, help="how far into the song to watch")
    p.add_argument("--again", action="store_true", help="keep what the app learnt of the song's timing")
    p.add_argument("--app", default="com.google.android.apps.youtube.music", help="the YouTube Music build to play it in")
    p.set_defaults(go=phone)
    args = parser.parse_args()
    args.go(args)


if __name__ == "__main__":
    main()
