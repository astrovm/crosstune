#!/usr/bin/env python3
"""Checks Crosstune's lyrics, timing, chords, readings and translations on the songs people play most.

  python3 scripts/songcheck/songcheck.py songs      # Apple Music's top 100 in two countries per continent
  python3 scripts/songcheck/songcheck.py audio      # their 30-second previews, as 16 kHz mono
  python3 scripts/songcheck/songcheck.py run        # the app's own lookups on each, through Gradle
  python3 scripts/songcheck/songcheck.py hear       # what's sung in each preview, and when (faster-whisper)
  python3 scripts/songcheck/songcheck.py timing     # the lyrics' times against what's heard
  python3 scripts/songcheck/songcheck.py grade      # batches for a model to grade; see grading.md
  python3 scripts/songcheck/songcheck.py summary    # what failed, per continent

Everything goes in build/songcheck/; each step goes on where it stopped. `run --gradle "sh run.sh"`
runs Gradle some other way, like in a container; `hear` and `timing` want faster-whisper and
anyascii, from scripts/lyrics/requirements.txt.
"""
import argparse
import difflib
import json
import shlex
import statistics
import subprocess
import sys
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "songcheck"
# Two big Apple Music countries per continent, as one alone misses much of what a continent plays.
COUNTRIES = {
    "us": "North America", "br": "South America", "de": "Europe", "jp": "Asia", "ng": "Africa", "au": "Oceania",
    "mx": "North America", "ar": "South America", "fr": "Europe", "kr": "Asia", "za": "Africa", "nz": "Oceania",
}
# More to check with `songs --countries`, each with a script or a language the first ones lack.
CONTINENTS = {
    **COUNTRIES,
    "in": "Asia", "id": "Asia", "th": "Asia", "vn": "Asia", "sa": "Asia", "il": "Asia",
    "tr": "Europe", "es": "Europe", "it": "Europe", "ru": "Europe", "gr": "Europe",
    "co": "South America", "cl": "South America", "eg": "Africa", "ke": "Africa",
}
TOP = 100
# How far off a line may start and still be on time.
ON_TIME_MS = 500
# Where a line is looked for in what was heard, around where its time puts it.
SEARCH_MS = 8000
# Whisper's times for sung words wander, so a song is only off when its lines agree on how far.
AGREE_MS = 500
# Small enough for a small model to read a batch of Japanese songs, readings and all, at once.
BATCH = 10
# Fewer words than this heard in a preview with lyrics is worth hearing again with a bigger model.
FEW_WORDS = 8


def get(url, tries=8):
    # Apple's chart feed often times out or fails for a while, then answers.
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "crosstune-songcheck"}), timeout=60) as response:
                return response.read()
        except OSError:
            if attempt == tries - 1:
                raise
            time.sleep(min(2 ** attempt, 30))


def songs(_):
    found, seen = [], set()
    charts = OUT / "charts"
    charts.mkdir(parents=True, exist_ok=True)
    for country, continent in COUNTRIES.items():
        # Kept, so a run that failed on one country doesn't ask for the others again.
        chart = charts / f"{country}.json"
        if not chart.exists():
            chart.write_bytes(get(f"https://rss.marketingtools.apple.com/api/v2/{country}/music/most-played/{TOP}/songs.json"))
        feed = json.loads(chart.read_text())
        ids = [entry["id"] for entry in feed["feed"]["results"]]
        # The store's own entries, for each song's length and preview; up to 200 asked at once.
        looked = {str(item["trackId"]): item for item in json.loads(get(f"https://itunes.apple.com/lookup?id={','.join(ids)}&country={country}"))["results"]}
        for rank, id in enumerate(ids, 1):
            item = looked.get(id)
            if item is None or id in seen:
                continue
            seen.add(id)
            found.append({
                "id": id, "country": country, "continent": continent, "rank": rank,
                "title": item["trackName"], "artist": item["artistName"], "lengthMs": item.get("trackTimeMillis"),
                "genre": item.get("primaryGenreName"), "previewUrl": item.get("previewUrl"),
            })
        print(f"{country}: {len(ids)} charted")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "songs.json").write_text(json.dumps(found, ensure_ascii=False, indent=1))
    print(f"{len(found)} songs, the ones in two charts once")


def load_songs():
    return json.loads((OUT / "songs.json").read_text())


def audio(_):
    folder = OUT / "audio"
    folder.mkdir(parents=True, exist_ok=True)
    for song in load_songs():
        pcm = folder / f"{song['id']}.pcm"
        if pcm.exists() or not song.get("previewUrl"):
            continue
        m4a = folder / f"{song['id']}.m4a"
        m4a.write_bytes(get(song["previewUrl"]))
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(m4a), "-ac", "1", "-ar", "16000", "-f", "s16le", str(pcm)], check=True)
        m4a.unlink()
    print(f"{len(list(folder.glob('*.pcm')))} previews")


def run(args):
    subprocess.run([*shlex.split(args.gradle), ":app:songCheck", f"-PsongcheckDir={OUT}"], cwd=ROOT, check=True)


def hear(args):
    from faster_whisper import WhisperModel
    import numpy as np
    folder = OUT / "heard"
    folder.mkdir(parents=True, exist_ok=True)
    models = {}

    def heard(pcm, name):
        model = models.get(name) or models.setdefault(name, WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=args.threads))
        samples = np.frombuffer(pcm.read_bytes(), dtype=np.int16).astype(np.float32) / 32768
        segments, info = model.transcribe(samples, word_timestamps=True, vad_filter=False, condition_on_previous_text=False)
        words = [{"ms": round(word.start * 1000), "word": word.word} for segment in segments for word in segment.words]
        return {"language": info.language, "words": words, "model": name}

    for pcm in sorted((OUT / "audio").glob("*.pcm")):
        out = folder / f"{pcm.stem}.json"
        if out.exists():
            continue
        found = heard(pcm, args.model)
        out.write_text(json.dumps(found, ensure_ascii=False))
        print(pcm.stem, found["language"], len(found["words"]))
    # A small model often hears little over loud music; a bigger one, once, where there are words to hear.
    for out in sorted(folder.glob("*.json")):
        found, report = json.loads(out.read_text()), OUT / "reports" / out.name
        if len(found["words"]) >= FEW_WORDS or found.get("model") == args.retry_model or not report.exists():
            continue
        if not json.loads(report.read_text()).get("lyrics", {}).get("lines"):
            continue
        again = heard(OUT / "audio" / f"{out.stem}.pcm", args.retry_model)
        out.write_text(json.dumps(again if len(again["words"]) > len(found["words"]) else {**found, "model": args.retry_model}, ensure_ascii=False))
        print(out.stem, "again", len(found["words"]), "->", len(again["words"]))


def plain(text):
    """
    Letters and digits only, in Latin letters and lowercase, so a line and what was heard compare:
    Whisper often writes Hindi in Latin letters or Urdu's script, where the lyrics are in Devanagari.
    """
    from anyascii import anyascii
    return "".join(c for c in anyascii(unicodedata.normalize("NFKC", text)).lower() if c.isalnum())


def preview_start(preview):
    """Where the preview starts in the song, by Shazam: the middle of the places it agrees on."""
    starts = [heard["startsAtMs"] for heard in preview.get("heard", []) if heard.get("startsAtMs") is not None]
    if not starts:
        return None
    middle = statistics.median(starts)
    agreeing = [start for start in starts if abs(start - middle) <= 1000]
    return round(statistics.median(agreeing)) if len(agreeing) * 2 > len(starts) else None


def line_timing(report, heard):
    """Each timed line sung in the preview, with how far its time is from when it's heard."""
    start = preview_start(report.get("preview", {}))
    lines = report.get("lyrics", {}).get("lines") or []
    if start is None or not lines or not heard["words"]:
        return None
    # What was heard as one run of letters, each with the time of the word it's in, in the song,
    # and where in it each word starts.
    letters, times, starts = "", [], []
    for word in heard["words"]:
        text = plain(word["word"])
        if text:
            starts.append(len(letters))
        letters += text
        times += [start + word["ms"]] * len(text)
    length = report["preview"]["lengthMs"]
    checked = []
    for line in lines:
        text = plain(line["text"])[:24]
        # Short lines, "baby", "yeah", match anywhere.
        if len(text) < 8 or not start + 500 <= line["ms"] <= start + length - 2000:
            continue
        # Sung again close by, as a chorus is, what's heard could be either time.
        if any(other is not line and plain(other["text"])[:24] == text and abs(other["ms"] - line["ms"]) <= SEARCH_MS for other in lines):
            continue
        best, at = 0.0, None
        # A line starts where a word does. Started inside the word before, a run matches as well
        # when the line's last letters are misheard, and takes that word's earlier time.
        for i in starts:
            if i > len(letters) - len(text):
                break
            if abs(times[i] - line["ms"]) > SEARCH_MS:
                continue
            ratio = difflib.SequenceMatcher(None, text, letters[i:i + len(text)], autojunk=False).ratio()
            if ratio > best:
                best, at = ratio, times[i]
        if best >= 0.8:
            checked.append({"text": line["text"], "ms": line["ms"], "heardMs": at, "offMs": at - line["ms"], "match": round(best, 2)})
    return {"previewStartMs": start, "lines": checked}


def copy_times(synced):
    """A timed copy's lines, as their first words to each time they start."""
    times = {}
    for line in synced.splitlines():
        found = re.match(r"\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)", line)
        if found:
            times.setdefault(plain(found[3])[:24], []).append(round((int(found[1]) * 60 + float(found[2])) * 1000))
    return times


def other_copies(report, lines):
    """
    How far LRCLIB's other timed copies of the song, as long as it, put its lines from where the
    app does: one each, by the middle of the lines they share. A copy timed the same to the
    millisecond is the app's own, and says nothing.
    """
    query = urllib.parse.urlencode({"track_name": report["title"], "artist_name": report["artist"]})
    try:
        copies = json.loads(get(f"https://lrclib.net/api/search?{query}", tries=3))
    except (OSError, ValueError):
        return []
    found = []
    for copy in copies:
        length = copy.get("duration")
        if not copy.get("syncedLyrics") or not length or not report.get("lengthMs") or abs(length * 1000 - report["lengthMs"]) > 2000:
            continue
        times = copy_times(copy["syncedLyrics"])
        # A line sung again, as a chorus is, is the time of it nearest.
        apart = [min((time - line["ms"] for time in times[key]), key=abs) for line in lines if len(key := plain(line["text"])[:24]) >= 8 and key in times]
        if len(apart) >= 3 and any(apart):
            found.append(round(statistics.median(apart)))
    return found


def timing(_):
    results = {}
    for path in sorted((OUT / "reports").glob("*.json")):
        report = json.loads(path.read_text())
        heard_path = OUT / "heard" / path.name
        if not heard_path.exists():
            continue
        found = line_timing(report, json.loads(heard_path.read_text()))
        if found is None:
            results[path.stem] = {"verdict": "unknown"}
            continue
        offs = [line["offMs"] for line in found["lines"]]
        if len(offs) < 3:
            verdict = "unknown"
        else:
            middle = statistics.median(offs)
            spread = statistics.median(abs(off - middle) for off in offs)
            verdict = "on time" if abs(middle) <= ON_TIME_MS else "off" if spread <= AGREE_MS else "unsure"
            found["medianOffMs"] = round(middle)
            found["spreadMs"] = round(spread)
            if verdict == "off":
                # Shazam can place the preview by another master of the song, with a longer or
                # shorter start, and Whisper's times wander: other copies side with the app then.
                copies = other_copies(report, report["lyrics"]["lines"])
                found["copiesApartMs"] = copies
                if copies and abs(statistics.median(copies)) < abs(statistics.median(copies) - middle):
                    verdict = "unsure"
        found["verdict"] = verdict
        results[path.stem] = found
    (OUT / "timing.json").write_text(json.dumps(results, ensure_ascii=False, indent=1))
    counts = {}
    for result in results.values():
        counts[result["verdict"]] = counts.get(result["verdict"], 0) + 1
    print(counts)


def grade(_):
    """Batches of what a model grades: the words found, a translation, readings, chords."""
    folder = OUT / "grade"
    folder.mkdir(parents=True, exist_ok=True)
    # Songs already in a batch stay there, so grading can start before the run ends.
    batched = {song["id"] for path in folder.glob("batch-*.json") for song in json.loads(path.read_text())}
    first = len(list(folder.glob("batch-*.json"))) + 1
    reports = [json.loads(path.read_text()) for path in sorted((OUT / "reports").glob("*.json"))]
    found = [report for report in reports if report.get("lyrics", {}).get("kind") == "found" and report["id"] not in batched]
    for number, start in enumerate(range(0, len(found), BATCH), first):
        batch = []
        for report in found[start:start + BATCH]:
            batch.append({
                "id": report["id"], "title": report["title"], "artist": report["artist"], "lengthMs": report.get("lengthMs"),
                "lyrics": report["lyrics"]["words"], "lyricsSource": report["lyrics"].get("source"),
                "translation": report.get("translation"), "readings": report.get("readings"), "chords": report.get("chords"),
            })
        (folder / f"batch-{number:02}.json").write_text(json.dumps(batch, ensure_ascii=False, indent=1))
    print(f"{len(found)} more songs with lyrics in {(len(found) + BATCH - 1) // BATCH} batches; grades go in {folder}/grades-NN.json")


def summary(_):
    reports = {path.stem: json.loads(path.read_text()) for path in sorted((OUT / "reports").glob("*.json"))}
    timings = json.loads((OUT / "timing.json").read_text()) if (OUT / "timing.json").exists() else {}
    grades = {}
    for path in sorted((OUT / "grade").glob("grades-*.json")):
        for graded in json.loads(path.read_text()):
            grades[graded["id"]] = graded
    lines = ["# Song check", ""]
    for country, continent in CONTINENTS.items():
        songs = [report for report in reports.values() if report["country"] == country]
        if not songs:
            continue
        lyrics = [song for song in songs if song["lyrics"]["kind"] == "found"]
        timed = [song for song in lyrics if song["lyrics"].get("lines")]
        judged = [timings[song["id"]] for song in timed if timings.get(song["id"], {}).get("verdict") in ("on time", "off")]
        unsure = sum(timings.get(song["id"], {}).get("verdict") == "unsure" for song in timed)
        chords = [song for song in lyrics if song.get("chords", {}).get("kind") == "found"]
        lines += [
            f"## {continent} ({country})", "",
            f"- Lyrics: {len(lyrics)} of {len(songs)} found, {len(timed)} timed",
            f"- Timing: {sum(t['verdict'] == 'on time' for t in judged)} of {len(judged)} checked on time, {unsure} unsure",
            f"- Chords: {len(chords)} of {len(lyrics)} found",
            "",
        ]
    problems = []
    for id, report in reports.items():
        kind = report["lyrics"]["kind"]
        if kind != "found":
            problems.append(("lyrics " + kind, report, ""))
        if timings.get(id, {}).get("verdict") == "off":
            problems.append(("timing off", report, f"median {timings[id]['medianOffMs']} ms"))
        for part, verdict in grades.get(id, {}).items():
            if isinstance(verdict, dict) and not verdict.get("ok", True):
                problems.append((f"{part}", report, verdict.get("why", "")))
    lines += ["## Problems", ""]
    for kind, report, why in sorted(problems, key=lambda problem: problem[0]):
        lines.append(f"- **{kind}**: {report['artist']} - {report['title']} ({report['country']} #{report['rank']}) {why}".rstrip())
    (OUT / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines[:60]))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=["songs", "audio", "run", "hear", "timing", "grade", "summary"])
    parser.add_argument("--gradle", default="./gradlew", help="how to run Gradle")
    parser.add_argument("--model", default="small", help="faster-whisper model, or a folder with one")
    parser.add_argument("--retry-model", default="medium", help="the bigger one for previews where little was heard")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--out", type=Path, help="where it all goes, if not build/songcheck/")
    parser.add_argument("--countries", help="the charts to take the songs from, as in,th,eg; the first twelve if not given")
    args = parser.parse_args()
    global OUT, COUNTRIES
    OUT = (args.out or OUT).resolve()
    if args.countries:
        COUNTRIES = {country: CONTINENTS[country] for country in args.countries.split(",")}
    globals()[args.step](args)


if __name__ == "__main__":
    sys.exit(main())
