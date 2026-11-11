"""
Scores rules that would tell an instrumental recording from a sung one, on what InstrumentalProbe saved (Issue #861).

    ./gradlew :app:songCheck -PsongcheckTest=com.astrovm.crosstune.songcheck.InstrumentalProbe -PsongcheckDir=DIR
    python3 scripts/songcheck/instrumental_rules.py DIR [-v]

DIR/songs.json lists the songs; each one's `problem` starts with "instrumental" or "sung". For each rule it
prints the instrumentals that get words and that the rule would fix, and the sung songs that get words and
that the rule would take them from. The bar for a rule is no sung song losing its words.
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

# As SongNames.saysInstrumental has it.
WORDS = r"(?:instrumental|instrumentale|inst\.?|off[ -]?vocal|backing track|minus one|伴奏|オフボーカル|纯音乐|純音樂|인스트루멘탈)"
BEFORE, AFTER = r"(?<![^\W_])", r"(?![^\W_])"
TAG = re.compile(
    r"[(\[（【][^()\[\]（）【】]*?" + BEFORE + WORDS + AFTER + r"[^()\[\]（）【】]*[)\]）】]"
    r"|\s[-–~〜]\s?[^-–~〜]*?" + BEFORE + WORDS + AFTER
    + r"|\soff[ -]?vocal(?: ver(?:sion|\.)?)?\s*$",
    re.I,
)
KARAOKE = re.compile(r"karaoke|カラオケ", re.I)
COVER_WORDS = r"\bcover(?:ed)?\b|\bin the style of\b|\boriginally (?:performed )?by\b|\bmade (?:famous|popular) by\b|カバー|歌ってみた|翻唱|커버"
COVER = [re.compile(r"[(\[【][^)\]】]*(?:" + COVER_WORDS + r")[^)\]】]*[)\]】]", re.I), re.compile(r"\s[-–]\s[^-–]*(?:" + COVER_WORDS + r").*$", re.I)]
INSTRUMENTAL_GENRES = {"Instrumental", "インストゥルメンタル", "演奏曲", "Instrumentale"}
SAME_LENGTH_S = 3.0


def says_instrumental(title):
    """Whether the title says no one sings in the recording, as SongNames.saysInstrumental."""
    return bool(TAG.search(title)) and not KARAOKE.search(title) and not any(cover.search(title) for cover in COVER)


def copies(song):
    """LRCLIB's copies of the recording as named, as the app picks them: exact names first, else close ones."""
    for search in song.get("lrclib", []):
        if search.get("track") == song["title"] and search.get("artist") == song["artist"]:
            found = search.get("copies", [])
            return [c for c in found if c["exact"]] or [c for c in found if c["belongs"]]
    return []


def signals(song):
    named = copies(song)
    length = song.get("lengthMs") or 0
    same = [c for c in named if length and c.get("seconds") and abs(c["seconds"] * 1000 - length) <= SAME_LENGTH_S * 1000]
    lyrics = song.get("lyrics", {})
    found = lyrics.get("kind") == "found"
    return {
        "n": len(named),
        "i": sum(c["instrumental"] for c in named),
        "same": len(same),
        "same_i": sum(c["instrumental"] for c in same),
        "tag": says_instrumental(song["title"]),
        "genre": song.get("genre", "") in INSTRUMENTAL_GENRES,
        "found": found,
        "untimed": found and lyrics.get("timedLines", 0) == 0,
        "source": lyrics.get("source"),
    }


def most(s):
    return s["i"] * 2 > s["n"]


RULES = {
    "R1 all LRCLIB copies instrumental": lambda s: s["n"] >= 1 and s["i"] == s["n"],
    "R2 most copies instrumental": most,
    "R3 any copy instrumental": lambda s: s["i"] >= 1,
    "R5 same-length copies mostly instrumental": lambda s: s["same_i"] * 2 > s["same"],
    "R6 most instrumental, words untimed": lambda s: most(s) and s["untimed"],
    "G1 store genre Instrumental": lambda s: s["genre"],
    "T title says so": lambda s: s["tag"],
    "T or R2 (this change)": lambda s: s["tag"] or most(s),
    "T or R2 or G1": lambda s: s["tag"] or most(s) or s["genre"],
}


def load(folder):
    songs = json.loads((folder / "songs.json").read_text())
    probed = []
    for song in songs:
        report = folder / "probe" / f"{song['id']}.json"
        if report.exists():
            probed.append({**song, **json.loads(report.read_text())})
    return probed


def score(probed):
    """Per rule: (instrumentals with words it fixes, sung songs with words it takes them from)."""
    instrumental = [p for p in probed if p["problem"].startswith("instrumental")]
    sung = [p for p in probed if p["problem"].startswith("sung")]
    table = {}
    for name, rule in RULES.items():
        fixed = [p for p in instrumental if signals(p)["found"] and rule(signals(p))]
        lost = [p for p in sung if signals(p)["found"] and rule(signals(p))]
        table[name] = (fixed, lost)
    return instrumental, sung, table


def main(argv):
    folder = Path(argv[1])
    instrumental, sung, table = score(load(folder))
    for name, group in (("instrumental", instrumental), ("sung", sung)):
        found = [p for p in group if signals(p)["found"]]
        print(f"{name}: words found for {len(found)} of {len(group)};", dict(Counter(signals(p)["source"] for p in found)))
    print(f"\n{'rule':45} {'fixed':>6} {'lost':>5}")
    for name, (fixed, lost) in table.items():
        print(f"{name:45} {len(fixed):>6} {len(lost):>5}  " + "; ".join(f"{p['artist']} - {p['title']}" for p in lost[:5]))
    if "-v" in argv:
        for p in instrumental + sung:
            s = signals(p)
            print(p["problem"][:28].ljust(28), "W" if s["found"] else "-", str(s["source"])[:26].ljust(26), f"n={s['n']} i={s['i']} tag={int(s['tag'])}", "|", p["title"][:40], "|", p["artist"][:25])


if __name__ == "__main__":
    main(sys.argv)
