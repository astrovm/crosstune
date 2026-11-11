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
import unicodedata
from collections import Counter
from pathlib import Path

# As SongNames.saysInstrumental has it. Both are tested against app/src/test/resources/instrumental-titles.tsv.
LETTER = r"[^\W_]"
WORD = re.compile(
    r"(?<!" + LETTER + r")(?<!" + LETTER + r"-)(?:instrumentale?|inst\.?|off[ -]?vocal|backing track|minus one|(?-i:MR)|인스트루멘탈|"
    r"(?:伴奏|オフボーカル|纯音乐|純音樂|純音楽)(?:版本?|バージョン)?)(?!" + LETTER + r")",
    re.I,
)
SHORT = re.compile(r"inst\.?|MR", re.I)
VOICE = re.compile(r"(?<!off )(?<!off-)\bvocals?\b|\ba[ -]?capp?ella\b", re.I)
PART_WORDS = {"with", "intro", "outro", "break", "interlude", "bridge", "from", "film", "movie"}
VERSION_WORDS = {"ver", "version", "versao"}
BRACKET = re.compile(r"[(\[【「〔]([^()\[\]【】「」〔〕]*)[)\]】」〕]")
DASH = re.compile(r"(?:(?<=\s)[-–]\s?|[~〜])([^-–~〜]*)")
EAST_ASIAN = "[぀-ヿ㐀-䶿一-鿿가-힯]"
OFF_VOCAL_AT_END = re.compile(
    r"(?:(?<=" + EAST_ASIAN + r")\s*off[ -]?vocal(?:\s+ver(?:sion|\.)?)?|\soff[ -]?vocal\s+ver(?:sion|\.)?)\s*$", re.I
)
KARAOKE = re.compile(r"karaoke|カラオケ", re.I)
COVER_WORDS = r"\bcover(?:ed)?\b|\bin the style of\b|\boriginally (?:performed )?by\b|\bmade (?:famous|popular) by\b|カバー|歌ってみた|翻唱|커버"
COVER = [re.compile(r"[(\[【][^)\]】]*(?:" + COVER_WORDS + r")[^)\]】]*[)\]】]", re.I), re.compile(r"\s[-–]\s[^-–]*(?:" + COVER_WORDS + r").*$", re.I)]
INSTRUMENTAL_GENRES = {"Instrumental", "インストゥルメンタル", "演奏曲", "Instrumentale"}
SAME_LENGTH_S = 3.0


def words(text):
    """Its words in Latin letters and digits, as SongNames.words: no marks, no case."""
    plain = "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c)).lower()
    return {w for w in re.split(r"[^a-z0-9]+", plain) if w}


def names_artist(before, artist):
    """Roughly SongNames.sameArtist: the text before a dash names the artist."""
    def key(name):
        return re.sub(r"[\W_]+", "", unicodedata.normalize("NFD", name).lower())
    one, other = key(before), key(artist)
    return bool(one) and bool(other) and (one == other or (min(len(one), len(other)) >= 5 and (one in other or other in one)))


def only_instrumental(tag):
    said = WORD.search(tag)
    if not said:
        return False
    rest = words(tag[: said.start()] + tag[said.end():])
    if rest & PART_WORDS:
        return False
    return not SHORT.fullmatch(said.group(0)) or rest <= VERSION_WORDS


def says_instrumental(title, artist=""):
    """Whether the title says no one sings in the recording, as SongNames.saysInstrumental."""
    name = unicodedata.normalize("NFKC", title)
    if KARAOKE.search(name) or any(cover.search(name) for cover in COVER) or VOICE.search(name):
        return False
    tags = [m.group(1) for m in BRACKET.finditer(name)]
    tags += [m.group(1) for m in DASH.finditer(name) if not (artist and names_artist(name[: m.start()], artist))]
    return any(only_instrumental(tag) for tag in tags) or bool(OFF_VOCAL_AT_END.search(name))


def copies(song):
    """LRCLIB's copies of the recording as named, as the app picks them: exact names first, else close ones."""
    for search in song.get("lrclib", []):
        if search.get("track") == song["title"] and search.get("artist") == song["artist"]:
            found = search.get("copies", [])
            return [c for c in found if c["exact"]] or [c for c in found if c["belongs"]]
    return []


FEATURING = re.compile(r"\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]", re.I)
EDITION = re.compile(r"(?:\s+[-–]\s+|\s*[(\[])[^()\[\]]*\b(?:remaster(?:ed)?|mono|stereo)\b[^()\[\]]*[)\]]?$", re.I)
SEPARATOR = re.compile(r"\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*|\s+(?:and|y|und|et)\s+", re.I)


def plain(text):
    """Letters and digits only, no marks and no case, as SongNames.normalize."""
    return "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c) and c.isalnum()).lower()


def artists(credit):
    """Each name in a credit and the credit whole, as SongNames.artists, roughly."""
    return {plain(re.sub(r"^the\s+", "", name, flags=re.I)) for name in SEPARATOR.split(credit) + [credit]} - {""}


def is_recording(copy, song):
    """The very recording, as LyricsFinder's Answer.isRecording: the probe saves it; older probes' copies are worked out here, roughly."""
    if "recording" in copy:
        return copy["recording"]
    if plain(EDITION.sub("", FEATURING.sub("", copy["title"]))) != plain(EDITION.sub("", FEATURING.sub("", song["title"]))):
        return False
    length = song.get("lengthMs")
    as_long = not copy.get("seconds") or not length or abs(copy["seconds"] * 1000 - length) <= SAME_LENGTH_S * 1000
    return bool(artists(copy["artist"]) & artists(song["artist"])) and as_long


def recordings(song):
    """LRCLIB's copies of this very recording, under the name it's played by."""
    for search in song.get("lrclib", []):
        if search.get("track") == song["title"] and search.get("artist") == song["artist"]:
            return [c for c in search.get("copies", []) if is_recording(c, song)]
    return []


def signals(song):
    named = copies(song)
    own = recordings(song)
    length = song.get("lengthMs") or 0
    same = [c for c in named if length and c.get("seconds") and abs(c["seconds"] * 1000 - length) <= SAME_LENGTH_S * 1000]
    lyrics = song.get("lyrics", {})
    found = lyrics.get("kind") == "found"
    return {
        "n": len(named),
        "i": sum(c["instrumental"] for c in named),
        "same": len(same),
        "same_i": sum(c["instrumental"] for c in same),
        "r_n": len(own),
        "r_i": sum(c["instrumental"] for c in own),
        "tag": says_instrumental(song["title"], song["artist"]),
        "genre": song.get("genre", "") in INSTRUMENTAL_GENRES,
        "found": found,
        "untimed": found and lyrics.get("timedLines", 0) == 0,
        "source": lyrics.get("source"),
    }


def most(s):
    return s["i"] * 2 > s["n"]


def marked(s, fewest=2):
    """Most of the recording's own copies marked, and at least [fewest] of them: the app's rule."""
    return s["r_i"] >= fewest and s["r_i"] * 2 > s["r_n"]


RULES = {
    "R1 all LRCLIB copies instrumental": lambda s: s["n"] >= 1 and s["i"] == s["n"],
    "R2 most copies instrumental": most,
    "R3 any copy instrumental": lambda s: s["i"] >= 1,
    "R5 same-length copies mostly instrumental": lambda s: s["same_i"] * 2 > s["same"],
    "R6 most instrumental, words untimed": lambda s: most(s) and s["untimed"],
    "G1 store genre Instrumental": lambda s: s["genre"],
    "T title says so": lambda s: s["tag"],
    "R2s the recording's own copies, 2 or more marked, most": marked,
    "R2s1 the recording's own copies, 1 or more marked, most": lambda s: marked(s, 1),
    "T or R2 (first version)": lambda s: s["tag"] or most(s),
    "T or R2s (this change)": lambda s: s["tag"] or marked(s),
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
            print(p["problem"][:28].ljust(28), "W" if s["found"] else "-", str(s["source"])[:26].ljust(26), f"n={s['n']} i={s['i']} own={s['r_n']} own_i={s['r_i']} tag={int(s['tag'])}", "|", p["title"][:40], "|", p["artist"][:25])


if __name__ == "__main__":
    main(sys.argv)
