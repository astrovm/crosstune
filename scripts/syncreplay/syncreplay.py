#!/usr/bin/env python3
"""Replays a real recording through the app's lyrics timing, in about half a minute, and says how
far from where each line is really sung the app showed it.

  python3 scripts/syncreplay/syncreplay.py prep ID LYRICS.json   # once per recording: see below
  python3 scripts/syncreplay/syncreplay.py run ID [--from MS]     # the app on it, through Gradle
  python3 scripts/syncreplay/syncreplay.py score ID [--from MS] [--placed]

ID is a YouTube id. LYRICS.json is the timed words the app finds for it, as [[ms, "line"], ...],
e.g. from the app's lyrics.json cache. `prep` fetches the recording (yt-dlp), separates the voice
(demucs), finds when each line is really sung (faster-whisper medium on the voice, word by word),
and what whisper.cpp tiny, the app's own model, hears in each stretch of it, as the phone would.
`run` plays it through the app (SyncReplayTest), from MS into the song: 0 is from the start, as the
app's clock is sure then; later is picked up partway. `--gradle "podman exec box ./gradlew"` runs
Gradle some other way, like in a container. Everything goes in build/syncreplay/, never in git.
Wants ffmpeg, yt-dlp, cmake, and demucs, faster-whisper and numpy in the Python running it.
"""
import argparse
import json
import re
import shlex
import statistics
import subprocess
import sys
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
ON_TIME_S = 0.5


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


def windows(song, wav, length):
    """What whisper.cpp tiny hears in each `length` ms from every STEP_MS, as the app hears it: 16-bit."""
    out = OUT / f"{song}.windows{length // 1000}.json"
    done = json.loads(out.read_text()) if out.exists() else {}
    a = audio(wav, "s16le")
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
    with open(OUT / f"{song}.truth.jsonl", "w") as f:
        for n, line in enumerate(lines):
            words = [j for j, (_, i) in enumerate(lyric) if i == n]
            if words and words[0] in at and sum(j in at for j in words) * 2 > len(words):
                f.write(json.dumps({"line": n, "text": line, "start": round(at[words[0]], 2)}) + "\n")


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
    asked.write_text(json.dumps({"song": args.id, "from": args.start}))
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
    args = parser.parse_args()
    args.go(args)


if __name__ == "__main__":
    main()
