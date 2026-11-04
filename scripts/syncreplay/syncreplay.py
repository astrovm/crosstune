#!/usr/bin/env python3
"""Replays a real recording through the app's lyrics timing, in about half a minute, and says how
far from where each line is really sung the app showed it.

  python3 scripts/syncreplay/syncreplay.py set [--cached]        # find and prep the song set
  python3 scripts/syncreplay/syncreplay.py all                   # replays, hearing what they ask for; per-song and overall table
  python3 scripts/syncreplay/syncreplay.py prep ID LYRICS.json   # once per recording: see below
  python3 scripts/syncreplay/syncreplay.py run ID [--from MS]     # the app on it, through Gradle
  python3 scripts/syncreplay/syncreplay.py run ID --like-phone    # as the last `phone` check of it went
  python3 scripts/syncreplay/syncreplay.py score ID [--from MS] [--placed]
  python3 scripts/syncreplay/syncreplay.py phone ID --seconds S [--again] [--app PACKAGE]
  python3 scripts/syncreplay/syncreplay.py hear ID --model base-q5_1    # then run --model base-q5_1

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
A media volume of 0 is raised to 1 while it plays, as at 0 the app hears only silence.
Each line is held to its own truth by its words, as the words the app shows, which the check keeps from
its cache, needn't be those given to prep: their lines break elsewhere, so their numbers mean other lines.
`run --fingerprints FILE` also replays recorded fingerprint answers, as a video heard when its words
open. The JSON has `wordsLength`, `videoLength`, `initial` and `events`: each answer has `at`,
`receivedAt`, `songMs`, `matchId` and optional `speed`. `at` and `receivedAt` are milliseconds on the
unadjusted player clock; `songMs` is the position Shazam returned for audio starting at `at`.
Use `--from` for the player position when the initial answer opened the words. Keep recordings
and these answers under build/, with the exact lyric sheet in ID.found.json.
`fingerprints ID [--kept DIR]` makes that JSON from what a `phone` check kept, and says the `--from`.
`run --like-phone` replays it as the last `phone` check went, from what it kept: where the app picked
the song up, how far its clock was behind, how long the phone took to work out each while, the words it
showed, and whether it heard the sound played itself, with Shizuku, or through the visualizer (`hear --clean`
first for the former). It then says how far each line is from when the phone lit it: only while
they match does the replay stand for the phone. The words a song's found with go in ID.found.json, which
every replay plays over those given to prep, word by word when they're timed so: `set` writes it too.
For ROCm Demucs only, set DEMUCS_PYTHON to a ROCm Python and DEMUCS_DEVICE=cuda;
without these it uses the current Python on CPU. Set WHISPER_PYTHON to a ROCm Python with openai-whisper for
truth on the GPU: it hears each song twice and keeps the lines both time alike, in about the time the CPU takes
to hear it once (measurements.md). Hearing stays on CPU, as it stands for the phone's.
`set --cached` resumes the existing set without another live search. Prep keeps truth for unchanged lyrics
and checkpoints every heard window atomically, so an interrupted song resumes too. Prep hears only every 20 s,
and a replay hears the rest it asks for, keeping it, so a change to the app's timing hears only what's new.
Wants ffmpeg, yt-dlp, cmake, curl, and demucs, faster-whisper and numpy in the Python running it.
"""
import argparse
import html
import json
from datetime import datetime
import os
import shutil
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "syncreplay"
HEARD = OUT / "heard"
HEARD_BUILT = threading.Lock()
# The app's speech model; others can be tried with `hear --model` and `run --model`.
APP_MODEL = "tiny-q5_1"
# Each while is heard on its own, on one thread, so how many at once only changes how long it takes.
HEARD_AT_ONCE = max(1, (os.cpu_count() or 4) - 2)
# Songs at once when a replay asks for more, as each has only a few stretches to hear.
SONGS_HEARD_AT_ONCE = 4
SEED_EVERY = 20
LANGUAGE_SEGMENTS = 4
MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{}.bin"
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
# Letters a text needs to name a song inside a longer title, so "Play" or a clock doesn't.
MIN_NAME = 8


# Written without spaces, so what's heard can split anywhere: each letter is matched by itself.
UNSPACED = "\u3040-\u30ff\u3400-\u9fff\uac00-\ud7a3"


def pieces(word):
    """What's matched of a word, in any writing: Latin letters without accents or punctuation, as one piece."""
    word = unicodedata.normalize("NFKC", word.lower().replace("’", "'"))
    bare = "".join(c for ch in word for c in (unicodedata.normalize("NFKD", ch) if ch < "\u0250" else ch) if ch >= "\u0250" or not unicodedata.combining(c))
    return re.findall(f"[{UNSPACED}]|[^{UNSPACED}]+", re.sub(r"[^\w']|_", "", bare))


def audio(path, kind="f32le"):
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", kind, "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32) if kind == "f32le" else np.frombuffer(raw, np.int16).astype(np.float32) / 32768


def heard_binary():
    # Songs are heard at once, and the first of each would otherwise all build it, over each other.
    with HEARD_BUILT:
        if not (HEARD / "heard").exists():
            subprocess.run(["cmake", "-S", str(Path(__file__).parent), "-B", str(HEARD), "-DCMAKE_BUILD_TYPE=Release"], check=True)
            subprocess.run(["cmake", "--build", str(HEARD), "-j", "4"], check=True)
    return HEARD / "heard"


def model_file(model):
    path = OUT / f"ggml-{model}.bin"
    if not path.exists():
        temporary = path.with_name(path.name + ".download")
        subprocess.run(["curl", "-fsSL", "--retry", "2", "-o", str(temporary), MODEL_URL.format(model)], check=True)
        temporary.replace(path)
    return path


def heard_file(song, length, model):
    """What `model` heard in each `length` ms of `song`: the app's model's is the plain one. A model
    named with ".clean" heard the recording itself rather than through the visualizer."""
    return OUT / f"{song}.heard{length // 1000}{'' if model == APP_MODEL else '.' + model}.json"  # SyncReplayTest reads the same names


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


def seed(starts):
    """What prep hears, so a replay has something near wherever it asks, and knows how long the song is."""
    return sorted(set(starts[::SEED_EVERY] + starts[-1:]))


def windows(song, wav, length, model=APP_MODEL, clean=False, wanted=None):
    """What whisper.cpp hears with `model` in each `length` ms from every STEP_MS, as the app hears it,
    or, `clean`, as it would from the sound played itself (AudioPlaybackCapture), 16 bits as it is."""
    out = heard_file(song, length, f"{model}.clean" if clean else model)
    done = json.loads(out.read_text()) if out.exists() else {}
    a = audio(wav) if clean else as_the_phone_hears(wav)
    binary = heard_binary()
    weights = model_file(model)
    starts = list(range(0, len(a) // 16 - length, STEP_MS))
    starts = [s for s in (wanted(starts) if wanted else starts) if str(s) not in done]
    language = sung_language(song)

    def hear(start):
        seg = a[start * 16:(start + length) * 16]
        # A model that can't run hears nothing, so it fails here rather than passing for silence.
        text = subprocess.run([str(binary), str(weights), language, ""], input=seg.tobytes(), capture_output=True, check=True, env={**os.environ, "HEARD_THREADS": "1"}).stdout.decode("utf-8", "replace")
        return start, [[int(ms), w] for ms, w in (x.split("\t", 1) for x in text.splitlines() if "\t" in x)]

    with ThreadPoolExecutor(HEARD_AT_ONCE) as pool:
        for start, words in pool.map(hear, starts):
            done[str(start)] = words
            atomic_text(out, json.dumps(done))


def atomic_text(path, text):
    """A killed writer leaves the last complete checkpoint usable."""
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text)
    temporary.replace(path)


def separated_voice(song, wav):
    vocals = OUT / "htdemucs" / song / "vocals.wav"
    if not vocals.exists():
        temporary = OUT / "separating"
        subprocess.run([os.environ.get("DEMUCS_PYTHON", sys.executable), "-m", "demucs", "--two-stems=vocals",
                        "-d", os.environ.get("DEMUCS_DEVICE", "cpu"), "-j", "1", "-o", str(temporary), str(wav)], check=True)
        vocals.parent.mkdir(parents=True, exist_ok=True)
        (temporary / "htdemucs" / song / "vocals.wav").replace(vocals)
        shutil.rmtree(temporary / "htdemucs" / song)
    return vocals


def truth_model():
    from faster_whisper import WhisperModel
    return WhisperModel("medium", device="cpu", compute_type="int8", cpu_threads=4)


# Languages with a writing of their own, which their lyrics show.
WRITING = {"ko": "[\uac00-\ud7a3]", "ja": "[\u3040-\u30ff\u3400-\u9fff]", "zh": "[\u3400-\u9fff]", "ru": "[\u0400-\u04ff]", "uk": "[\u0400-\u04ff]",
           "ar": "[\u0600-\u06ff]", "he": "[\u0590-\u05ff]", "th": "[\u0e00-\u0e7f]", "el": "[\u0370-\u03ff]", "hi": "[\u0900-\u097f]"}


def sung_language(song):
    """What the song is sung in, kept in ID.language, standing in for what the app tells from its words:
    the app hears in that language, and heard in English, a song in Spanish lines up with nothing.
    The sound alone can be wrong, as Dynamite sounds Korean, so a language the lyrics have none of the writing of is out."""
    kept = OUT / f"{song}.language"
    if not kept.exists() or not kept.read_text().strip():
        language, _, likely = truth_model().detect_language(audio(separated_voice(song, OUT / f"{song}.wav")), vad_filter=True, language_detection_segments=LANGUAGE_SEGMENTS)
        words = " ".join(text for _, text in json.loads((OUT / f"{song}.lyrics.json").read_text()))
        fits = (name for name, _ in likely if name not in WRITING or re.search(WRITING[name], words))
        atomic_text(kept, next(fits, language))
    return kept.read_text()


def words_heard(vocals, language):
    """Each word Whisper medium hears in the voice and when, once per hearing: twice on a GPU with WHISPER_PYTHON
    (measurements.md), once with faster-whisper on the CPU, where a second would take minutes more."""
    gpu = os.environ.get("WHISPER_PYTHON")
    if gpu:
        said = subprocess.run([gpu, "-I", str(Path(__file__).with_name("whisper_gpu.py")), str(vocals), language],
                              check=True, stdout=subprocess.PIPE, text=True).stdout
        hearings = json.loads(said)
    else:
        segments, _ = truth_model().transcribe(audio(vocals), language=language, word_timestamps=True, condition_on_previous_text=False, beam_size=5)
        hearings = [[(w.word, w.start) for s in segments for w in s.words]]
    return [[(piece, start) for word, start in words for piece in pieces(word)] for words in hearings]


def line_starts(heard, lines):
    """When each line starts in these heard words, matched in order to the lyrics. A line counts only when its
    first word and most of the rest match."""
    lyric = [(piece, i) for i, line in enumerate(lines) for word in line.split() for piece in pieces(word)]
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
    return starts


def agreed(hearings):
    """The lines every hearing times alike. A hearing can lose a stretch of the song, and then a repeated line
    matches its next time around, off by a whole verse with nothing else to show for it (The Police's chorus)."""
    first, *rest = hearings
    return {n: start for n, start in first.items() if all(n in other and abs(other[n] - start) <= ON_TIME_S for other in rest)}


def truth(song, wav, lines):
    """When each line with words is really sung, from Whisper medium's word times on the separated voice."""
    vocals = separated_voice(song, wav)
    hearings = [line_starts(heard, lines) for heard in words_heard(vocals, sung_language(song))]
    write_truth(song, lines, voiced(vocals, agreed(hearings)))


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
    atomic_text(OUT / f"{song}.truth.jsonl", "".join(
        json.dumps({"line": n, "text": line, "start": round(starts[n], 2)}) + "\n"
        for n, line in enumerate(lines) if n in starts))


def measured(song, phase, work):
    started = time.monotonic()
    work()
    print(f"{song} {phase}: {time.monotonic() - started:.2f} s", flush=True)


def prep(args):
    OUT.mkdir(parents=True, exist_ok=True)
    lyrics = json.loads(Path(args.lyrics).read_text())
    kept = OUT / f"{args.id}.lyrics.json"
    reference = OUT / f"{args.id}.truth.jsonl"
    if reference.exists() and (not kept.exists() or json.loads(kept.read_text()) != lyrics):
        reference.replace(reference.with_name(reference.name + ".previous"))
    atomic_text(kept, json.dumps(lyrics))
    wav = OUT / f"{args.id}.wav"
    if not wav.exists():
        downloading = OUT / "downloading"
        downloading.mkdir(exist_ok=True)
        downloaded = downloading / wav.name
        # An interrupted ffmpeg conversion can leave a WAV that yt-dlp would otherwise reuse.
        downloaded.unlink(missing_ok=True)
        subprocess.run(["yt-dlp", "-q", "-f", "bestaudio", "-x", "--audio-format", "wav", "-o", str(downloading / f"{args.id}.%(ext)s"), f"https://music.youtube.com/watch?v={args.id}"], check=True)
        downloaded.replace(wav)
    if not (OUT / f"{args.id}.truth.jsonl").exists():
        measured(args.id, "demucs", lambda: separated_voice(args.id, wav))
        measured(args.id, "truth", lambda: truth(args.id, wav, [text for _, text in lyrics if text.strip()]))
    for length in LENGTHS:
        measured(args.id, f"heard{length // 1000}", lambda: windows(args.id, wav, length, wanted=seed))


def hear_asked(song, replays):
    """Hears each stretch these replays of the song asked for and got only something near to: how many new ones.
    What's heard in one decides what's asked next, so it takes a few replays to settle (measurements.md)."""
    plays = [json.loads((OUT / name).read_text()) for name in replays if (OUT / name).exists()]
    new = 0
    for length in LENGTHS:
        asked = [h["from"] for play in plays for h in play.get("heard", []) if h["seconds"] * 1000 == length]
        if not asked:
            continue
        done = json.loads(heard_file(song, length, APP_MODEL).read_text())
        last = max(map(int, done))
        missing = sorted({min(max(0, (at + STEP_MS // 2) // STEP_MS * STEP_MS), last) for at in asked} - set(map(int, done)))
        if missing:
            windows(song, OUT / f"{song}.wav", length, wanted=lambda starts, missing=missing: [s for s in missing if s in starts])
            new += len(missing)
    return new


def run(args):
    phone_lit = like_phone(args) if args.like_phone else None
    # What SyncReplayTest plays, read from here, so with no replay asked for, it's skipped.
    asked = OUT / "replay.json"
    replay = {"song": args.id, "from": args.start, "work": args.work, "behind": args.behind, "heard": "" if args.model == APP_MODEL else f".{args.model}"}
    if args.behind_again is not None:
        replay["behindAgain"] = args.behind_again
    if getattr(args, "fingerprints", None):
        replay["fingerprints"] = json.loads(Path(args.fingerprints).read_text())
    plays = ([f"{args.id}.from{args.start}.first.shown.json"] if args.behind_again is not None else []) + [f"{args.id}.from{args.start}.shown.json"]
    while True:
        asked.write_text(json.dumps(replay))
        try:
            done = subprocess.run(shlex.split(args.gradle) + ["-q", ":app:testDebugUnitTest", "--tests", "*SyncReplayTest*", "--rerun"], cwd=ROOT, capture_output=True, text=True)
        finally:
            asked.unlink()
        if done.returncode:
            sys.exit(done.stdout[-3000:] + done.stderr[-3000:])
        if args.model != APP_MODEL or not hear_asked(args.id, plays):
            break
    shown = score(args)
    if phone_lit is not None:
        compare(phone_lit, shown)


def like_phone(args):
    """Sets the replay up as the last `phone` check of the song went, from what it kept: where the app
    picked the song up, how far its clock was behind, how long the phone took to work out each while
    heard, and whether it heard the sound played itself (Shizuku's playback capture) or through the
    visualizer. Gives when the phone lit each line, to hold the replay to."""
    kept = OUT / "phone" / args.id
    log = (kept / "log.txt").read_text()
    said = re.search(r"said (\d+) at \d+ playing true", log)
    args.start = int(said.group(1)) if said else 0
    lit, args.behind = score_phone(args.id, kept)
    args.behind = round(args.behind)
    # Each while is heard right after the last is worked out, so the gaps between them are the working out.
    whiles = sorted((int(re.search(r"heard-(\d+)", f.name).group(1)), f.stat().st_size // 2 * 1000 // 16000) for f in kept.glob("heard-*.pcm"))
    gaps = [after - (start + ms) for (start, ms), (after, _) in zip(whiles, whiles[1:])]
    args.work = round(statistics.median(gaps)) if gaps else args.work
    # Only playback capture logs what it captured; the visualizer's sound is heard as `hear` makes it by default.
    clean = "captured " in log
    args.model = f"{APP_MODEL}.clean" if clean else APP_MODEL
    # The words the phone showed, not those prep was given.
    if (kept / "lyrics.json").exists():
        atomic_text(OUT / f"{args.id}.found.json", json.dumps(found_words(args.id, kept)))
    if not heard_file(args.id, LENGTHS[0], args.model).exists():
        print(f"The phone heard the sound played itself, and the replay hears through the visualizer: for the same, first `hear {args.id} --clean`.")
        args.model = APP_MODEL
    print(f"As on the phone: from {args.start} ms, the clock {args.behind} ms behind, {args.work} ms to work out each while, "
          f"hearing {'the sound played (Shizuku)' if clean else 'through the visualizer'}")
    return lit


def compare(phone_lit, shown):
    """How far the replay's line times are from the phone's: the replay only stands for the phone while they match."""
    both = sorted(k for k in phone_lit if k in shown)
    for k in both:
        print(f"{k:2d} phone {phone_lit[k]:6.1f}  replay {shown[k]:6.1f}  {shown[k] - phone_lit[k]:+5.1f}")
    off = [abs(shown[k] - phone_lit[k]) for k in both]
    only = sorted(set(phone_lit) ^ set(shown))
    print(f"The replay against the phone: {len(both)} lines lit in both, median {statistics.median(off):.2f} s apart, worst {max(off):.1f} s"
          + (f"; lit in only one: {only}" if only else ""))


def score(args):
    shown = json.loads((OUT / f"{args.id}.from{args.start}.shown.json").read_text())
    sung = sung_lines(args.id, shown["lines"])
    # The app's line to the n-th line with words.
    nth = {}
    for i, text in enumerate(shown["lines"]):
        if text.strip():
            nth[i] = len(nth)
    first = lit_first(shown)
    lags = {k: first[k] - sung[k] for k in sung if k in first}
    # With no words found there's no line to tell a line sung by: truth still says how many went unshown.
    line_of = {k: i for i, k in nth.items()}
    for k in sorted(sung):
        text = shown["lines"][line_of[k]][:34] if k in line_of else ""
        print(f"{k:2d} sung {sung[k]:6.1f}  " + (f"shown {first[k]:6.1f} lag {lags[k]:+5.1f}  {text}" if k in lags else f"never shown   {text}"))
    if args.placed:
        for p in shown["placed"]:
            print(f"at {p['at'] / 1000:6.1f}: " + " ".join(f"{nth[i]}:{(p['times'][i] / 1000 - sung[nth[i]]):+.1f}" for i in nth if nth[i] in sung))
    # The lines sung once the words were first put right: what can be right as the song plays.
    fixed = shown["placed"][1]["at"] / 1000 if len(shown["placed"]) > 1 else 0
    live = [lag for k, lag in lags.items() if sung[k] > fixed]
    how = f"median {statistics.median(abs(x) for x in live):.2f} s, worst {max(live, key=abs):+.1f} s" if live else "none shown"
    print(f"{args.id} from {args.start} ms: lines sung after the first fix at {fixed:.1f} s: "
          f"{sum(abs(x) <= ON_TIME_S for x in live)}/{len(live)} within {ON_TIME_S} s, {how}; never shown {sum(k not in first for k in sung)}")
    early = [lag for lag in lags.values() if lag < -ON_TIME_S]
    print(f"shown sung early, before its voice: {len(early)}/{len(lags)} lines" + (f", by up to {-min(early):.1f} s" if early else ""))
    return first


def lined_up(ours, theirs):
    """Which of [ours] are which of [theirs], by index: those starting with the same word, by their words matched
    in order, as many as can be, and with most of the shorter one's words the same. Other words for a song may
    break its lines elsewhere or spell them otherwise: "In the wild West End, sometime" and "In the wild west end sometime"."""
    a = [(piece, i) for i, line in enumerate(ours) for word in line.split() for piece in pieces(word)]
    b = [(piece, j) for j, line in enumerate(theirs) for word in line.split() for piece in pieces(word)]
    most = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) - 1, -1, -1):
        for j in range(len(b) - 1, -1, -1):
            most[i][j] = most[i + 1][j + 1] + 1 if a[i][0] == b[j][0] else max(most[i + 1][j], most[i][j + 1])
    same, i, j = {}, 0, 0
    while i < len(a) and j < len(b):
        if a[i][0] == b[j][0]:
            same[i] = j
            i += 1
            j += 1
        elif most[i + 1][j] >= most[i][j + 1]:
            i += 1
        else:
            j += 1
    first_a, first_b = {}, {}
    for n, (_, line) in enumerate(a):
        first_a.setdefault(line, n)
    for n, (_, line) in enumerate(b):
        first_b.setdefault(line, n)
    pairs = {}
    for line, start in first_a.items():
        other = same.get(start)
        if other is None or first_b.get(b[other][1]) != other:
            continue
        ours_words = [n for n, (_, l) in enumerate(a) if l == line]
        theirs_words = {n for n, (_, l) in enumerate(b) if l == b[other][1]}
        if sum(same.get(n) in theirs_words for n in ours_words) * 2 > min(len(ours_words), len(theirs_words)):
            pairs[line] = b[other][1]
    return pairs


def sung_lines(song, lines):
    """When the n-th of [lines] with words is sung, by n. Truth is for the words given to prep; words the app really
    showed may be others, so each line's truth is found by its words. One with no truth isn't scored."""
    given = [line for _, line in json.loads((OUT / f"{song}.lyrics.json").read_text()) if line.strip()]
    # Words with no lines yet, as found untimed, are all dark, as nothing placed them.
    shown = [line for line in lines if line.strip()] or given
    same = lined_up(given, shown)
    sung = {}
    for row in (OUT / f"{song}.truth.jsonl").read_text().splitlines():
        truth = json.loads(row)
        if truth["line"] in same:
            sung[same[truth["line"]]] = truth["start"]
    return sung


def found_words(song, kept):
    """The words the app showed in a `phone` check, as its cache has them. It looks ahead to the next song's
    too, so it's those most like the words given to prep."""
    given = [line for _, line in json.loads((OUT / f"{song}.lyrics.json").read_text())]
    found = [json.loads(entry[1]) for entry in json.loads((kept / "lyrics.json").read_text())]
    return max(found, key=lambda words: len(lined_up(given, [line[1] for line in words["lines"]])))


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


def names(screen, title):
    """Whether [screen] has a text naming the song [title] plays as: the app may tell it by a shorter name
    than the music app's, as "Every Little Thing She Does Is Magic" for "Police, The   Every Little Thing She Does Is Magic Audio Flac"."""
    # The main screen's history may name it too, with the words not open at all.
    if 'text="Link or song"' in screen:
        return False
    wanted = re.sub(r"\W", "", title.lower())
    shown = (re.sub(r"\W", "", html.unescape(text).lower()) for text in re.findall(r'text="([^"]*)"', screen))
    return any(text and (text == wanted or (len(text) >= MIN_NAME and text in wanted)) for text in shown)


def shows(title, tries=40):
    """Whether something on screen names the song [title], soon: a recording named badly is heard first, which takes a while."""
    for _ in range(tries):
        try:
            shell("uiautomator", "dump", "/sdcard/window.xml")
            if names(shell("cat", "/sdcard/window.xml"), title):
                return True
        except subprocess.CalledProcessError:
            pass
        time.sleep(0.5)
    return False


def playing(app):
    """The song [app] plays, its title, and when by the phone's clock it was at 0 ms; None till it plays."""
    session = shell("dumpsys", "media_session").split(f"package={app}")
    if len(session) < 2:
        return None
    state = re.search(r"state=PLAYING\(3\), position=(\d+),.*?updated=(\d+)", session[1][:3000])
    # Title, artist, album: a title may have commas of its own ("Police, The   Every Little Thing..."), so it's all before the last two.
    title = re.search(r"description=(.*), [^,\n]*, [^,\n]*$", session[1][:3000], re.MULTILINE)
    return (title.group(1), int(state.group(2)) - int(state.group(1))) if state and title else None


def media_volume(level=None):
    """The phone's media volume, set to [level] first when given."""
    if level is not None:
        shell("cmd", "media_session", "volume", "--stream", "3", "--set", str(level))
    return int(re.search(r"volume is (\d+)", shell("cmd", "media_session", "volume", "--stream", "3", "--get")).group(1))


def phone(args):
    # At volume 0 what the app hears of the music is silence, so there'd be no singing to place lines by.
    # 1 is enough: the app hears the music at full level from there on. The volume is put back after.
    volume = media_volume()
    if volume == 0:
        media_volume(1)
    try:
        watch(args)
    finally:
        media_volume(volume)


def watch(args):
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
    song_set = OUT / "set.json"
    songs = json.loads(song_set.read_text()) if song_set.exists() else []
    wanted = next((song["title"] for song in songs if song.get("id") == args.id), None)
    for _ in range(60):
        song = playing(args.app)
        # A restarted player may still publish the previous song while the requested link opens.
        if song and (wanted is None or "".join(pieces(wanted)) in "".join(pieces(song[0]))):
            break
        time.sleep(0.5)
    else:
        sys.exit(f"{args.app} didn't play {args.id}")
    zero = song[1]
    # Picked up as it plays, as when Crosstune is opened on a song already playing.
    shell("monkey", "-p", DEV_APP, "-c", "android.intent.category.LAUNCHER", "1")
    # The words of what's playing, not a song of the same name under Recent, which would open it again.
    if not tap("Lyrics of what's playing"):
        sys.exit("Couldn't open the words in Crosstune: is the phone unlocked?")
    # Only now, as a music app just started may still tell the last song's name as it starts this one.
    title = playing(args.app)[0]
    if not shows(title):
        sys.exit(f"Crosstune opened the words of another song, not {title}")
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
    # The words the app really showed, which needn't be those prep was given.
    (kept / "lyrics.json").write_bytes(adb("exec-out", "run-as", DEV_APP, "cat", "cache/lyrics.json"))
    shell("run-as", DEV_APP, "find", "cache", "-name", "heard-*.pcm", "-delete")
    score_phone(args.id, kept)


def behind(song, kept, lit):
    """How far behind what's heard the app's clock is: each while kept, found in the recording, against where the app said the song was."""
    files = sorted(kept.glob("heard-*.pcm"))
    if not files:
        print("The app heard nothing to check its clock by, so it's taken as right.")
        return 0
    import numpy as np
    offs = []
    for f in files:
        started = int(re.search(r"heard-(\d+)", f.name).group(1))
        clock = min(lit, key=lambda e: abs(e[1] - started))
        app = clock[2] + started - clock[1]
        heard = np.frombuffer(f.read_bytes(), np.int16).astype(np.float32)[:16000 * 8]
        if not heard.any():
            sys.exit(f"{f.name} is silent: the app heard nothing of the music. Was the media volume 0?")
        n = 1 << int(np.ceil(np.log2(len(song) + len(heard))))
        match = np.fft.irfft(np.fft.rfft(song, n) * np.conj(np.fft.rfft(heard, n)), n)[:len(song) - len(heard)]
        # A running sum, as sliding one while over a whole song took minutes for each heard.
        energy = np.concatenate(([0.0], np.cumsum(song.astype(np.float64) ** 2)))
        loud = np.sqrt(np.maximum(energy[len(heard):] - energy[:-len(heard)], 0))[:len(match)] + 1e-9
        at = int(np.argmax(match / loud)) / 16
        offs.append(at - app)
        print(f"heard from {at / 1000:6.1f} s, the app said {app / 1000:6.1f} s: {(at - app) / 1000:+.2f} s behind")
    return statistics.median(offs)


def phone_lines(log):
    """Each line lit on the player's clock, before fingerprint calibration moves it."""
    player = None
    lit = []
    for row in log.splitlines():
        said = re.search(r"said -?\d+ at \d+ playing (true|false), kept (-?\d+) at (\d+)", row)
        if said:
            player = (int(said[2]), int(said[3]), said[1] == "true")
        shown = re.search(r"lit (-?\d+) at (\d+) pos (-?\d+)", row)
        if shown:
            line, at, pos = map(int, shown.groups())
            if player is not None:
                pos = player[0] + (at - player[1] if player[2] else 0)
            lit.append((line, at, pos))
    return lit


def phone_answers(log, words_length, video_length):
    """What Shazam told the app in a `phone` check, on the player's clock, as `run --fingerprints` replays it.
    The log's own time says when each answer came, as its first lines don't. The first answer's `recognized`
    row is from a later app: before it, it's where the first line lit was."""
    rows = log.splitlines()
    def wall(row):
        day, clock = row.split()[:2]
        when = datetime.strptime(f"2026-{day} {clock}", "%Y-%m-%d %H:%M:%S.%f")
        return round(when.timestamp() * 1000)
    said = [re.search(r"said -?\d+ at \d+ playing true, kept (-?\d+) at (\d+)", row) for row in rows]
    said = [(row, match) for row, match in zip(rows, said) if match]
    if not said:
        raise SystemExit("The phone's log has no clock from the music app, so its answers can't be placed.")
    row, last = said[-1]
    zero = int(last[2]) - int(last[1])
    to_elapsed = int(last[2]) - wall(row)
    def answer(row, song, at, match, speed):
        return dict(at=at - zero, receivedAt=wall(row) + to_elapsed - zero, songMs=song, matchId=match, speed=speed)
    events, initial = [], None
    for row in rows:
        heard = re.search(r"(fingerprint|recognized) (\d+) at (\d+) match (\S+)(?: received \d+ speed ([\d.]+))?", row)
        if not heard:
            continue
        told = answer(row, int(heard[2]), int(heard[3]), heard[4], float(heard[5] or 1))
        if heard[1] == "fingerprint":
            events.append(told)
        elif initial is None:
            initial = told
    if initial is None:
        lit = next((m for m in map(re.compile(r"lit -?\d+ at (\d+) pos (-?\d+)").search, rows) if m), None)
        if lit is None or not events:
            raise SystemExit("The phone's log has no answer to start from.")
        initial = dict(at=int(lit[1]) - zero, receivedAt=int(lit[1]) - zero, songMs=int(lit[2]), matchId=events[0]["matchId"], speed=1.0)
    return dict(wordsLength=words_length, videoLength=video_length, initial=initial, events=events)


def fingerprints(args):
    kept = Path(args.kept) if args.kept else OUT / "phone" / args.id
    log = (kept / "log.txt").read_text()
    lyrics = (kept / "lyrics.json").read_text()
    words = OUT / f"{args.id}.found.json"
    length = json.loads(words.read_text())["length"] if words.exists() else found_words(args.id, kept)["length"]
    video = re.search(r"duration:(\d+)", lyrics)
    if not video:
        raise SystemExit("The phone's cache doesn't say how long the video is.")
    answers = phone_answers(log, length, int(video[1]))
    out = Path(args.out) if args.out else OUT / f"{args.id}.fingerprints.json"
    atomic_text(out, json.dumps(answers))
    print(f"{out}: {len(answers['events'])} answers. Replay it with `run {args.id} --from {answers['initial']['at']} --fingerprints {out}`")


def score_phone(song_id, kept):
    lit = phone_lines((kept / "log.txt").read_text())
    if not lit:
        sys.exit("No lines lit in the log: was the dev app reinstalled after the log was added, and the words open?")
    lag = behind(audio(OUT / f"{song_id}.wav", "s16le") * 32768, kept, lit)
    # The app's lines are the words it showed, kept from its cache; truth counts only those with words.
    words = [line[1] for line in found_words(song_id, kept)["lines"]] if (kept / "lyrics.json").exists() else [line for _, line in json.loads((OUT / f"{song_id}.lyrics.json").read_text())]
    sung = sung_lines(song_id, words)
    nth = {i: k for k, i in enumerate(i for i, line in enumerate(words) if line.strip())}
    first = {}
    for i, _, pos in lit:
        if nth.get(i) is not None and nth[i] not in first:
            first[nth[i]] = (pos + lag) / 1000
    # A line sung after the check stopped isn't dark.
    played_to = (max(pos for _, _, pos in lit) + lag) / 1000
    sung = {k: at for k, at in sung.items() if at <= played_to}
    lags = {k: first[k] - sung[k] for k in sung if k in first}
    for k in sorted(sung):
        text = words[next(i for i in nth if nth[i] == k)][:34]
        print(f"{k:2d} sung {sung[k]:6.1f}  " + (f"lit {first[k]:6.1f} lag {lags[k]:+5.1f}  {text}" if k in lags else f"never lit   {text}"))
    # Truth has only some of the lines: any line timed before the check stopped and never lit is dark too.
    timed = [(line[0] / 1000, i) for i, line in enumerate(found_words(song_id, kept)["lines"]) if line[1].strip()] if (kept / "lyrics.json").exists() else []
    lit_lines = {i for i, _, _ in lit}
    span = (min(pos for i, _, pos in lit if i >= 0) / 1000, played_to) if any(i >= 0 for i, _, _ in lit) else (0, 0)
    dark = [i for at, i in timed if span[0] <= at <= span[1] and i not in lit_lines]
    print(f"Lines dark among all timed from the first lit to the end: {len(dark)} of {sum(span[0] <= at <= span[1] for at, _ in timed)}: {dark}")
    print(f"{song_id} on the phone, the app's clock {lag / 1000:.2f} s behind: {sum(abs(x) <= ON_TIME_S for x in lags.values())}/{len(lags)} within {ON_TIME_S} s, "
          f"{sum(abs(x) <= 1 for x in lags.values())}/{len(lags)} within 1 s, {f"median {statistics.median(abs(x) for x in lags.values()):.2f} s" if lags else "none lit"}; never lit {sum(k not in first for k in sung)}")
    return first, lag


def find_set(args):
    """Finds the songs of songs.tsv as the app does (SyncSetTest), then preps each not prepped yet."""
    OUT.mkdir(parents=True, exist_ok=True)
    if not args.cached:
        asked = OUT / "set.asked"
        OUT.mkdir(parents=True, exist_ok=True)
        asked.write_text("")
        try:
            done = subprocess.run(shlex.split(args.gradle) + ["-q", ":app:testDebugUnitTest", "--tests", "*SyncSetTest*", "--rerun"], cwd=ROOT, capture_output=True, text=True)
        finally:
            asked.unlink()
        if done.returncode:
            sys.exit(done.stdout[-3000:] + done.stderr[-3000:])
    found = json.loads((OUT / "set.json").read_text())
    for song in found:
        print(f"{song.get('id') or 'not on YouTube Music':12} {song.get('source') or 'no words':14} {'timed' if song.get('timed') else 'untimed':8} {song['title']} - {song['artist']}")
    if args.find_only:
        return
    failures = []
    for song in found:
        if song.get("id") and song.get("lines") and not complete(song["id"]):
            print(f"Prepping {song['id']}: {song['title']} - {song['artist']}", flush=True)
            try:
                prep(argparse.Namespace(id=song["id"], lyrics=str(OUT / f"{song['id']}.lyrics.json")))
            except (subprocess.CalledProcessError, OSError) as error:
                failures.append(song["id"])
                print(f"Failed {song['id']}: {error}", file=sys.stderr, flush=True)
    if failures:
        sys.exit(f"Prep failed for {len(failures)} songs: {', '.join(failures)}. Rerun set --cached to resume.")


def complete(song):
    """Both hearing lengths must contain every window, including after an interrupted write."""
    if not all((OUT / f"{song}.{suffix}").exists() for suffix in ("wav", "language", "lyrics.json", "truth.jsonl")):
        return False
    if not (OUT / f"{song}.language").read_text().strip():
        return False
    duration = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                               "-of", "default=nw=1:nk=1", str(OUT / f"{song}.wav")]))
    # The visualizer discards the last incomplete mixer block, as windows() does.
    milliseconds = int(round(duration * 48000)) // BLOCK * (BLOCK // 48)
    for length in LENGTHS:
        path = heard_file(song, length, APP_MODEL)
        if not path.exists():
            return False
        done = json.loads(path.read_text())
        if any(str(start) not in done for start in seed(list(range(0, milliseconds - length, STEP_MS)))):
            return False
    return True


def prepped():
    """Every song with complete prep, including both lengths of hearing."""
    return sorted(f.name.removesuffix(".truth.jsonl") for f in OUT.glob("*.truth.jsonl")
                  if complete(f.name.removesuffix(".truth.jsonl")))


def run_all(args):
    """Every prepped song replayed from the start in one go, and how its lines came out against when they're sung."""
    songs = prepped()
    asked = OUT / "replay.json"
    asked.write_text(json.dumps({"songs": [{"song": song, "from": 0, "work": args.work, "behind": args.behind, "heard": args.heard} for song in songs]}))
    # Their names too, one a line, as SyncReplayTest reads them before it can read JSON.
    names = OUT / "replay.songs"
    wanted = asked.read_text()
    while True:
        asked.write_text(wanted)
        names.write_text("\n".join(songs) + "\n")
        try:
            done = subprocess.run(shlex.split(args.gradle) + ["-q", ":app:testDebugUnitTest", "--tests", "*SyncReplayTest*", "--rerun"], cwd=ROOT, capture_output=True, text=True)
        finally:
            asked.unlink()
            names.unlink()
        if done.returncode:
            sys.exit(done.stdout[-3000:] + done.stderr[-3000:])
        if args.heard:
            break
        with ThreadPoolExecutor(SONGS_HEARD_AT_ONCE) as pool:
            if not sum(pool.map(lambda song: hear_asked(song, [f"{song}.from0.shown.json"]), songs)):
                break
    titles = {song.get("id"): song["title"] for song in json.loads((OUT / "set.json").read_text())} if (OUT / "set.json").exists() else {}
    totals = dict(lines=0, close=0, on=0, dark=0, early=0)
    all_lags = []
    lyric_lines = 0
    print(f"{'song':12} {'lines':>5} {'0.3 s':>6} {'0.5 s':>6} {'dark':>5} {'early':>5} {'worst':>6}  title")
    for song in songs:
        shown = json.loads((OUT / f"{song}.from0.shown.json").read_text())
        sung = sung_lines(song, shown["lines"])
        first = lit_first(shown)
        lags = [first[k] - sung[k] for k in sung if k in first]
        all_lags.extend(lags)
        lyric_lines += sum(bool(text.strip()) for text in shown["lines"])
        row = dict(lines=len(sung), close=sum(abs(x) <= 0.3 for x in lags), on=sum(abs(x) <= ON_TIME_S for x in lags),
                   dark=sum(k not in first for k in sung), early=sum(x < -ON_TIME_S for x in lags))
        for key in totals:
            totals[key] += row[key]
        worst = f"{max(lags, key=abs):+.1f}" if lags else "-"
        print(f"{song:12} {row['lines']:5} {row['close']:6} {row['on']:6} {row['dark']:5} {row['early']:5} {worst:>6}  {titles.get(song, '')[:40]}")
    t = totals
    worst = f"{max(all_lags, key=abs):+.1f}" if all_lags else "-"
    print(f"{'all':12} {t['lines']:5} {t['close']:6} {t['on']:6} {t['dark']:5} {t['early']:5} {worst:>6}  "
          f"{100 * t['close'] / max(t['lines'], 1):.0f}% within 0.3 s, {100 * t['on'] / max(t['lines'], 1):.0f}% within {ON_TIME_S} s, {t['dark']} dark")
    print(f"Reference coverage: {t['lines']}/{lyric_lines} nonempty lyric lines. Unmatched lines have no truth and are not scored.")


def lit_first(shown):
    """When each line with words was first shown, after the one before it, by its place among them."""
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
    return first


def hear(args):
    for length in LENGTHS:
        windows(args.id, OUT / f"{args.id}.wav", length, args.model, args.clean)


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
            # A Galaxy S22 Ultra took 3.9 to 4.6 s for each 20 s; it varies, and where each later stretch is heard with it.
            p.add_argument("--work", type=int, default=4500, help="ms the phone takes to work out what it heard")
            # YouTube Music's was 450 to 880 ms behind, each time a little different.
            p.add_argument("--behind", type=int, default=0, help="ms the music app's clock is behind what it plays")
            p.add_argument("--model", default=APP_MODEL, help="whose hearing to play, made first with `hear --model`")
            p.add_argument("--behind-again", type=int, help="play it a second time, keeping what was learnt, with the clock this far behind; scored is the second")
            p.add_argument("--fingerprints", help="JSON recording of the initial recognition and later answers on the player clock")
            p.add_argument("--like-phone", action="store_true", help="as the last `phone` check of it went, held to the lines it lit; sets --from, --behind, --work and --model")
    p = steps.add_parser("fingerprints")
    p.add_argument("id")
    p.add_argument("--kept", help="the folder a `phone` check kept, when not its own")
    p.add_argument("--out", help="where to write them, when not ID.fingerprints.json")
    p.set_defaults(go=fingerprints)
    p = steps.add_parser("set")
    p.add_argument("--gradle", default="./gradlew --max-workers=4")
    p.add_argument("--find-only", action="store_true", help="find the songs and their words, without prepping them")
    p.add_argument("--cached", action="store_true", help="resume the songs already found in set.json without finding them again")
    p.set_defaults(go=find_set)
    p = steps.add_parser("all")
    p.add_argument("--gradle", default="./gradlew --max-workers=4")
    p.add_argument("--work", type=int, default=4500, help="ms the phone takes to work out what it heard")
    p.add_argument("--behind", type=int, default=0, help="ms the music app's clock is behind what it plays")
    p.add_argument("--heard", default="", help="replay another set of what was heard: .thin5 reads ID.heard20.thin5.json")
    p.set_defaults(go=run_all)
    p = steps.add_parser("hear")
    p.add_argument("id")
    p.add_argument("--model", default=APP_MODEL, help="a whisper.cpp model, e.g. base-q5_1, to hear the recording with as the app would")
    p.add_argument("--clean", action="store_true", help="hear the recording itself, not through the visualizer; then run --model MODEL.clean")
    p.set_defaults(go=hear)
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
