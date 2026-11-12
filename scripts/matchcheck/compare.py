#!/usr/bin/env python3
"""Compare, song by song, what main's "song in another script" rule and this branch's open, without the network.

    python3 scripts/matchcheck/compare.py OUT [-v]

The songs and answers are what collect.py kept; analyze.py says how each is read. Each song is looked
for as the app does when no store names it in its own script, as analyze.py does. The rules:

1. main: the first result by the artist with a letter past ASCII, within 3 s of a known length, opens.
2. now, with ChatGPT: the fits are within 3 s, by the artist, and say no other version (analyze.py).
   The one fit a video names both ways opens. Otherwise the fits go first in ChatGPT's pick ("pick").
3. now, without ChatGPT, or when it can't say in time: as 2, and also the fits, when all are one name,
   open the first of them, unless it names itself in Latin letters with no word of the song's name.
   Otherwise nothing opens, and the app offers the link or a search.

"pick" is what ChatGPT answers, which this can't tell.
"""
import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze  # noqa: E402 - the same reading of the answers
import collect  # noqa: E402 - the same cache, so nothing is asked again

MAIN_WINDOW_MS = 3_000
NOW_WINDOW = analyze.window_ms("±3000 ms (now)")


def main_rule(song, results, lengths):
    """ExactMatcher.isTheSongInAnotherScript on main: the first that fits opens."""
    wanted = analyze.artists(song["artist"])
    for r in results:
        if r["id"] and r["lengthMs"] is not None and any(c.isalpha() and ord(c) > 0x7f for c in r["name"]) and \
                any(abs(r["lengthMs"] - ms) <= MAIN_WINDOW_MS for ms, _ in lengths) and analyze.artists(r["details"][0]) & wanted:
            return "open", r
    return "nothing", None


def now_rule(song, results, videos, lengths, chatgpt):
    """ExactMatcher.inAnotherScript and picked, as now."""
    fits = analyze.fits_of(song, results, lengths, NOW_WINDOW)
    if not fits:
        return "nothing", None
    named = [r for r in fits if analyze.named_both_ways(song, r, videos)]
    if len(named) == 1:
        return "open", named[0]
    maybe = named + [r for r in fits if r not in named]
    if len({analyze.normalize(analyze.bare_title(r["name"])) for r in maybe}) == 1 and not analyze.names_another(song, maybe[0]) and not chatgpt:
        return "open", maybe[0]
    return ("pick", maybe[0]) if chatgpt else ("nothing", None)


def outcome(kind, row, right):
    if kind == "nothing":
        return "nothing"
    return f"{kind} {'right' if right(row) else 'wrong'}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    with open(os.path.join(args.out, "songs.json"), encoding="utf-8") as kept:
        songs = json.load(kept)
    tables = {}
    for song in songs:
        results = analyze.rows(collect.ytm(args.out, song["query"], collect.YTM_SONGS))
        videos = analyze.rows(collect.ytm(args.out, song["query"], collect.YTM_VIDEOS))
        native = {analyze.bare(n["title"]) for n in song["native"]} | {analyze.normalize(n["title"]) for n in song["native"]}

        def right(row):
            return (song["artist"], row["name"]) in analyze.RIGHT_BY_EYE or any(
                analyze.bare(n) in native or analyze.normalize(n) in native or analyze.normalize(n) == analyze.normalize(song["title"])
                for n in analyze.title_names(row["name"]))

        reaches = not analyze.found_by_name(song, results)
        deezer = analyze.deezer_seconds(song)
        apple = [(ms, False) for ms in song["editionsMs"]] or [(song["lengthMs"], False)]
        for who in ("all songs, the rule alone", "only the songs that reach it"):
            if who.startswith("only") and not reaches:
                continue
            for source, lengths in (("Apple ms", apple), ("Deezer s", [(deezer, True)] if deezer else [])):
                if not lengths:
                    continue
                was = outcome(*main_rule(song, results, lengths), right)
                with_gpt = outcome(*now_rule(song, results, videos, lengths, chatgpt=True), right)
                without_gpt = outcome(*now_rule(song, results, videos, lengths, chatgpt=False), right)
                tables.setdefault((who, source), Counter())[(was, with_gpt, without_gpt)] += 1
                if args.verbose and (was != "nothing" or with_gpt != "nothing"):
                    print(f"  [{who}, {source}] {song['artist']} - {song['title']}: main {was}, now {with_gpt} / no ChatGPT {without_gpt}", file=sys.stderr)
    for (who, source), counts in sorted(tables.items()):
        print(f"\n{who}, {source}: {sum(counts.values())} songs\n")
        print("| main | now, with ChatGPT | now, without ChatGPT | Songs |")
        print("|---|---|---|---|")
        for (was, with_gpt, without_gpt), n in sorted(counts.items(), key=lambda item: -item[1]):
            print(f"| {was} | {with_gpt} | {without_gpt} | {n} |")


if __name__ == "__main__":
    main()
