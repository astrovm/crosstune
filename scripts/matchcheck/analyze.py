#!/usr/bin/env python3
"""Measure the matcher's "song in another script" rule on what collect.py kept, without the network.

    python3 scripts/matchcheck/analyze.py OUT [-v]

Each song is looked for as the app does when no store names it in its own script: its names are
only the US store's, in Latin letters, and its lengths are Apple's editions, or else Deezer's whole
seconds. The song results YouTube Music gave are then checked as ExactMatcher.theSongInAnotherScript
checks them, mirrored here: a result fits when it's by the artist, says no other version, is in
another script and is as long as a known length. A fit is right when its bare name is one of the
names Apple's JP, KR or TW store gives the recording of the same length to the millisecond.

It prints two tables:
1. The other-script signals: for each, on how many songs it holds, and how often it holds for a
   wrong result.
2. The length pairs: YouTube Music's whole seconds against Apple's milliseconds and Deezer's whole
   seconds, for the results known to be right.
"""
import argparse
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import collect  # noqa: E402 - the same cache, so nothing is asked again

VERSION_WORDS = {
    "cover", "covers", "covered", "karaoke", "live", "unplugged", "mtv", "vivo", "directo", "session", "sessions",
    "concert", "tour", "remix", "mix", "instrumental", "inst", "acoustic", "acustico", "piano", "guitar",
    "bass", "drum", "drums", "chords", "tab", "tabs", "tutorial", "lesson", "practice", "reaction", "slowed",
    "reverb", "sped", "nightcore", "8d", "demo", "medley", "mashup", "amv", "mmd", "ver", "tribute", "ktv",
    "acapella", "acappella", "cappella", "capella", "8bit", "orgel", "musicbox",
}
# SongNames.VERSION_MARKS: each mark, and the Latin words that mean it, which the song's own name may say.
VERSION_MARKS = {
    "カラオケ": {"karaoke"}, "ライブ": {"live"}, "弾き語り": set(), "歌ってみた": {"cover"}, "演奏してみた": {"cover"},
    "弾いてみた": {"cover"}, "叩いてみた": {"cover"}, "ピアノ": {"piano"}, "ギター": {"guitar"}, "カバー": {"cover"},
    "メドレー": {"medley"}, "練習": {"practice"}, "オルゴール": {"orgel", "musicbox"}, "アカペラ": {"acapella", "cappella"},
    "アコースティック": {"acoustic"}, "翻唱": {"cover"}, "伴奏": {"instrumental", "karaoke"}, "伴唱": {"karaoke"},
    "卡拉ok": {"karaoke"}, "커버": {"cover"}, "라이브": {"live"}, "노래방": {"karaoke"},
    "串烧": {"medley"}, "串燒": {"medley"}, "메들리": {"medley"},
    "offvocal": {"instrumental", "karaoke"}, "tvsize": set(), "オフボーカル": {"instrumental", "karaoke"},
    "オフヴォーカル": {"instrumental", "karaoke"}, "インスト": {"inst", "instrumental"}, "tvサイズ": set(), "テレビサイズ": set(),
    "纯音乐": {"instrumental"}, "純音樂": {"instrumental"}, "인스트": {"inst", "instrumental"}, "반주": {"instrumental", "karaoke"},
    "リミックス": {"remix"}, "混音": {"remix"}, "리믹스": {"remix"}, "演唱会": {"live", "concert"}, "演唱會": {"live", "concert"},
}
# SongNames.Mark.HOLD_A_MARK: words that only hold a mark's letters, as "ドライブ" holds "ライブ".
HOLD_A_MARK = re.compile("インストール|ドライブ|ライブラリ|ディスカバー|リカバー|드라이브|라이브러리|디스커버|커버리지|반주자")
ARTIST_SPLIT = re.compile(r"\s*(?:,|&|、|×|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\band\b)\s*", re.IGNORECASE)
BRACKETS = re.compile(r"\s*[(\[（【「『].*?[)\]）】」』]\s*")


def normalize(text):
    folded = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))
    return "".join(c for c in folded.lower() if c.isalnum())


def latin(text):
    return all(not c.isalpha() or "LATIN" in unicodedata.name(c, "") for c in text)


def artists(text):
    return {normalize(part) for part in ARTIST_SPLIT.split(text) if normalize(part)}


def words(text):
    return set(re.split(r"[^\w]+", unicodedata.normalize("NFKD", text).lower())) - {""}


def mark_found(mark, name):
    """SongNames.Mark.foundIn: the mark's letters in a row, anything but letters and digits between them; one in Latin
    letters only as a word of its own."""
    text = HOLD_A_MARK.sub(" ", unicodedata.normalize("NFKC", name).lower())
    letters = [c for c in unicodedata.normalize("NFKC", mark).lower() if c.isalnum()]
    pattern = r"(?:[^\w]|_)*".join(re.escape(c) for c in letters)
    a_word = all("LATIN" in unicodedata.name(c, "") for c in letters)

    def goes_on(c):
        return c is not None and ("LATIN" in unicodedata.name(c, "") or c.isdigit() or unicodedata.category(c) == "Mn")
    for m in re.finditer(pattern, text):
        before = text[m.start() - 1] if m.start() > 0 else None
        after = text[m.end()] if m.end() < len(text) else None
        if not a_word or not goes_on(before) and not goes_on(after):
            return True
    return False


# SongNames.SHORT_FOR: what a name writes short for the song's own word.
SHORT_FOR = {"version": "ver", "instrumental": "inst"}


def says_another_version(name, title, artist, album=""):
    own = words(title) | words(artist) | words(album)
    own |= {SHORT_FOR[w] for w in own if w in SHORT_FOR}
    if (words(name) - own) & VERSION_WORDS:
        return True
    return any(mark_found(mark, name) and not mark_found(mark, title) and not mark_found(mark, artist) and not means & own
               for mark, means in VERSION_MARKS.items())


def title_names(name):
    """The name, and each half of "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"."""
    halves = [half for half in re.split(r"\s+[-–—/]\s+|\s*[（(]\s*|\s*[)）]\s*", name) if half.strip()]
    return [name] + halves


def bare(name):
    return normalize(BRACKETS.sub(" ", name))


def rows(answer):
    """YouTube Music's results as the app reads them: the video, the name, the second line split up and the length."""
    found = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "musicResponsiveListItemRenderer":
                    found.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(answer or {})
    out = []
    for item in found:
        columns = ["".join(run.get("text", "") for run in col.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs", [])) for col in item.get("flexColumns", [])]
        details = (columns[1] if len(columns) > 1 else "").split(" • ")
        length = None
        parts = details[-1].strip().split(":")
        if 2 <= len(parts) <= 3 and all(p.isdigit() for p in parts):
            length = 0
            for p in parts:
                length = length * 60 + int(p)
            length *= 1000
        out.append({"id": item.get("playlistItemData", {}).get("videoId"), "name": columns[0] if columns else "", "details": details, "lengthMs": length})
    return out


def found_by_name(song, results):
    """Whether the app finds it before the rule: a result named as the song, by its artist."""
    title = normalize(song["title"])
    wanted = artists(song["artist"])
    return any(r["id"] and any(normalize(n) == title or bare(n) == bare(song["title"]) for n in title_names(r["name"]) + app_title_names(r["name"]))
               and artists(r["details"][0]) & wanted for r in results)


def deezer_seconds(song):
    title, wanted = bare(song["title"]), artists(song["artist"])
    for d in song.get("deezer", []):
        if d.get("duration") and bare(d.get("title") or "") == title and artists(d.get("artist") or "") & wanted:
            return d["duration"] * 1000
    return None


def window_ms(name):
    """Which of a result's lengths, against a known one, count as the same recording."""
    return {
        "<1000 both ways": (lambda off, whole: -1000 < off < 1000),
        "-500..+1500 ms, ±1000 s": (lambda off, whole: -1000 <= off <= 1000 if whole else -500 <= off <= 1500),
        "-700..+1000 ms, ±1000 s": (lambda off, whole: -1000 <= off <= 1000 if whole else -700 <= off <= 1000),
        "±3000 ms (now)": (lambda off, whole: -3000 <= off <= 3000),
    }[name]


def fits_of(song, results, lengths, inside):
    """The results the rule finds fit, as it checks them knowing only the song's Latin names and its US album."""
    fits = [r for r in results if r["id"] and r["lengthMs"] is not None and not latin(r["name"]) and
            any(inside(r["lengthMs"] - ms, whole) for ms, whole in lengths) and
            artists(r["details"][0]) & artists(song["artist"]) and
            not says_another_version(f"{r['name']} {r['details'][1] if len(r['details']) > 2 else ''}", song["title"], song["artist"], song["album"])]
    return list({r["id"]: r for r in fits}.values())


# Results checked by eye that the names alone can't tell are right: China's simplified letters for
# Taiwan's traditional ones, or Korea's name for one the stores give only in Chinese.
RIGHT_BY_EYE = {
    ("YOONA", "덕수궁 돌담길의 봄 Deoksugung Stonewall Walkway (feat. 10cm)"),
    ("Sally Yeh & A-Lin", "爱的可能 (Live)"),
    # The song itself, and its Director's Edit, which Japan's store doesn't name for the US store's id.
    ("Yoko Takahashi", "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"),
    ("Yoko Takahashi", "残酷な天使のテーゼ (Directors Edit Version) - The Cruel Angel's Thesis(Directors Edit Version)"),
    # Named the same, with the Latin name after a dash, which the halves split up.
    ("Kenshi Yonezu", "烏 - Raven - Karasu - Raven"),
    ("Keisuke Kuwata & Yumi Matsutoya", "Kissin' Christmas (クリスマスだからじゃない) 2023 - Kissin' Christmas (Christmas Dakarajanai) 2023"),
    ("Toshiki Kadomatsu", "After 5 Crash / Tokyo Tower / 初恋 / I'll Do My Best - After 5 Crash / Tokyo Tower / Hatsukoi / I'll Do My Best"),
    # Korea's names for the songs the stores give only in Latin letters.
    ("Lee Sun Hee", "아! 옛날이여"),
    ("Lee Sun Hee", "A girl's pray (소녀의 기도)"),
}

WINDOWS = ("<1000 both ways", "-500..+1500 ms, ±1000 s", "-700..+1000 ms, ±1000 s", "±3000 ms (now)")


TRAILING_TAG = re.compile(r"(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$")


def bare_title(title):
    """SongNames.bareTitle: without what it adds in brackets or after a dash, at its end."""
    name = title.strip()
    while True:
        shorter = TRAILING_TAG.sub("", name).strip()
        if shorter == name or not shorter:
            return name
        name = shorter


def app_title_names(title):
    """SongNames.titleNames, but for the old Recent titles and the wide space: the name, and its two halves by a dash."""
    halves = [half.strip() for half in title.split(" - ") if half.strip()]
    return [title] + (halves if len(halves) == 2 else [])


def names_another(song, row):
    """ExactMatcher.namesAnother: the row names itself in Latin letters too, with no word of the song's name."""
    said = {w for w in re.split(r"[^a-z0-9]+", unicodedata.normalize("NFKD", song["title"]).lower()) if w}
    latin_words = [ws for ws in ({w for w in re.split(r"[^a-z0-9]+", unicodedata.normalize("NFKD", n).lower()) if w}
                                 for n in app_title_names(row["name"]) if latin(n)) if ws]
    return bool(latin_words) and not any(ws & said for ws in latin_words)


def named_both_ways(song, row, videos):
    """
    As ExactMatcher.namedBothWays: a video names the result's own name, in its script, and the song's
    Latin one, "Unconditional (無條件) - Eason Chan".
    """
    latin_name = normalize(bare_title(song["title"]))
    own = [normalize(bare_title(half)) for half in app_title_names(row["name"]) if not latin(half)]
    own = [name for name in own if name]
    return bool(latin_name) and any(latin_name in normalize(v["name"]) and any(o in normalize(v["name"]) for o in own) for v in videos if v["id"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    with open(os.path.join(args.out, "songs.json"), encoding="utf-8") as kept:
        songs = json.load(kept)
    pairs = []
    stats = {}
    reached = 0
    for song in songs:
        query = song["query"]
        results = rows(collect.ytm(args.out, query, collect.YTM_SONGS))
        videos = rows(collect.ytm(args.out, query, collect.YTM_VIDEOS))
        native = {bare(n["title"]) for n in song["native"]} | {normalize(n["title"]) for n in song["native"]}
        albums = {normalize(n["album"]) for n in song["native"]} | {normalize(song["album"])}
        wanted = artists(song["artist"]) | {a for n in song["native"] for a in artists(n["artist"])}

        def right(row):
            return (song["artist"], row["name"]) in RIGHT_BY_EYE or any(
                bare(n) in native or normalize(n) in native or normalize(n) == normalize(song["title"]) for n in title_names(row["name"]))

        # Length pairs: the right result on the same release, by its album, so the same recording.
        deezer = deezer_seconds(song)
        same = next((r for r in results if r["id"] and r["lengthMs"] is not None and right(r) and artists(r["details"][0]) & wanted and
                     len(r["details"]) > 2 and normalize(r["details"][1]) in albums), None)
        if same:
            pairs.append({"song": f"{song['artist']} - {song['title']}", "row": same["name"], "ytm": same["lengthMs"], "apple": song["lengthMs"], "deezer": deezer})
        reaches = not found_by_name(song, results)
        reached += reaches
        apple_lengths = [(ms, False) for ms in song["editionsMs"]] or [(song["lengthMs"], False)]
        for who in ("all songs, the rule alone", "only the songs that reach it"):
            if who.startswith("only") and not reaches:
                continue
            for source, lengths in (("Apple ms", apple_lengths), ("Deezer s", [(deezer, True)] if deezer else [])):
                for name in WINDOWS:
                    key = (who, source, name)
                    s = stats.setdefault(key, {k: 0 for k in ("songs", "any", "single", "single_wrong", "many", "first_right", "album", "album_wrong", "latin_half", "latin_half_wrong", "video", "video_wrong", "opens", "opens_wrong")})
                    if not lengths:
                        continue
                    s["songs"] += 1
                    fits = fits_of(song, results, lengths, window_ms(name))
                    if not fits:
                        continue
                    s["any"] += 1
                    s["first_right"] += right(fits[0])
                    if len(fits) == 1:
                        s["single"] += 1
                        s["single_wrong"] += not right(fits[0])
                        if args.verbose and not right(fits[0]):
                            print(f"  wrong single [{who}, {source}, {name}]: {song['artist']} - {song['title']} ({song['lengthMs']} ms) -> {fits[0]['name']} | {' • '.join(fits[0]['details'])} ; native {[n['title'] for n in song['native']]}", file=sys.stderr)
                    else:
                        s["many"] += 1
                    confirmed = [r for r in fits if named_both_ways(song, r, videos)]
                    if len(confirmed) == 1:
                        s["opens"] += 1
                        s["opens_wrong"] += not right(confirmed[0])
                        if args.verbose and not right(confirmed[0]):
                            print(f"  wrong open [{who}, {source}, {name}]: {song['artist']} - {song['title']} -> {confirmed[0]['name']}", file=sys.stderr)
                    for r in fits:
                        # Knowing only the Latin names, the album to compare is the US store's.
                        if len(r["details"]) > 2 and normalize(r["details"][1]) == normalize(song["album"]):
                            s["album"] += 1
                            s["album_wrong"] += not right(r)
                        if any(normalize(h) == normalize(song["title"]) for h in title_names(r["name"])[1:]):
                            s["latin_half"] += 1
                            s["latin_half_wrong"] += not right(r)
                        if named_both_ways(song, r, videos):
                            s["video"] += 1
                            s["video_wrong"] += not right(r)
    print(f"{len(songs)} songs with a Latin name in the US store and their own script's in JP, KR or TW; {reached} not found by the Latin name, so they reach the rule\n")
    print("| Songs looked for | Lengths | Window | Songs | Any fit | One fit | One fit, wrong | Many fit | First fit right | Album equal (wrong) | Latin half (wrong) | Video names both (wrong) | Opens: one fit a video names (wrong) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (who, source, name), s in sorted(stats.items()):
        print(f"| {who} | {source} | {name} | {s['songs']} | {s['any']} | {s['single']} | {s['single_wrong']} | {s['many']} | {s['first_right']} | {s['album']} ({s['album_wrong']}) | {s['latin_half']} ({s['latin_half_wrong']}) | {s['video']} ({s['video_wrong']}) | {s['opens']} ({s['opens_wrong']}) |")
    print(f"\n{len(pairs)} length pairs on the same release, YouTube Music minus the other, in ms\n")
    print("| Against | Pairs | Min | p5 | Median | p95 | Max | Within -500..+1500 | Within -700..+1000 | Within ±1000 |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for name, offs in (("Apple ms", sorted(p["ytm"] - p["apple"] for p in pairs)), ("Deezer s", sorted(p["ytm"] - p["deezer"] for p in pairs if p["deezer"]))):
        if offs:
            n = len(offs)
            print(f"| {name} | {n} | {offs[0]} | {offs[n // 20]} | {offs[n // 2]} | {offs[n * 19 // 20]} | {offs[-1]} | {sum(-500 <= o <= 1500 for o in offs)} | {sum(-700 <= o <= 1000 for o in offs)} | {sum(-1000 <= o <= 1000 for o in offs)} |")
    if args.verbose:
        for p in pairs:
            print(f"  {p['song']} | {p['row']} | ytm {p['ytm']} apple {p['apple']} ({p['ytm'] - p['apple']:+}) deezer {p['deezer']} ({'-' if p['deezer'] is None else format(p['ytm'] - p['deezer'], '+')})", file=sys.stderr)


if __name__ == "__main__":
    main()
