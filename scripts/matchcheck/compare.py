#!/usr/bin/env python3
"""Compare, song by song, what main's "song in another script" rule and this branch's open, without the network.

    python3 scripts/matchcheck/compare.py OUT [-v]

The songs and answers are what collect.py kept; analyze.py says how each is read. Each song is looked
for as the app does when no store names it in its own script, as analyze.py does. The rules:

1. main: the first result by the artist with a letter past ASCII, within 3 s of a known length, opens.
2. now, with ChatGPT: the fits are within 3 s, by the artist, and say no other version (analyze.py).
   The one fit a video names both ways opens. Otherwise main's row, then the fits, go first in ChatGPT's
   pick ("pick"); main's row opens without asking when only its album kept it out.
3. now, without ChatGPT, or when it can't say in time: as 2, and also main's row, when it's in another
   script than Latin, its own name says no other version, and it doesn't name itself in Latin letters
   with no word of the song's name; and when all the candidates are one name, or the fits are, or only its
   album kept it out. Otherwise nothing opens, and the app offers the link or a search.

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


def opened_of(song, results, lengths):
    """ExactMatcher.mainOpens: by the artist, as long, with a letter past ASCII; main opened the first."""
    wanted = analyze.artists(song["artist"])
    return [r for r in results if r["id"] and r["lengthMs"] is not None and any(c.isalpha() and ord(c) > 0x7f for c in r["name"]) and
            any(abs(r["lengthMs"] - ms) <= MAIN_WINDOW_MS for ms, _ in lengths) and analyze.artists(r["details"][0]) & wanted]


def main_rule(song, results, lengths):
    """ExactMatcher.isTheSongInAnotherScript on main: the first that fits opens."""
    opened = opened_of(song, results, lengths)
    return ("open", opened[0]) if opened else ("nothing", None)


def bare(row):
    return analyze.normalize(analyze.bare_title(row["name"]))


def now_rule(song, results, videos, lengths, chatgpt):
    """ExactMatcher.inAnotherScript, alone and picked, as now, with no store name in another script and no video that
    names the song, which this doesn't model."""
    opened = opened_of(song, results, lengths)
    candidates = [r for r in opened if not analyze.latin(r["name"])]
    unsaid = lambda name: not analyze.says_another_version_of(name, [song["title"]], song["artist"])  # noqa: E731
    fits = [r for r in candidates if unsaid(" | ".join(filter(None, (r["name"], analyze.album_said(r, song)))))]
    as_main = opened[0] if opened and opened[0] in candidates and unsaid(opened[0]["name"]) else None
    other = list({r["id"]: r for r in fits}.values())
    named = [r for r in other if analyze.named_both_ways(song, r, videos)]
    if len(named) == 1:
        return "open", as_main if as_main and bare(as_main) == bare(named[0]) else named[0]
    # Main's row first, unless its own name says it's another version: with no store name in another script, none is
    # in a known script.
    first = opened[:1] if opened and unsaid(opened[0]["name"]) else []
    maybe = list({r["id"]: r for r in first + named + other}.values())
    alone, kind = None, None
    if as_main and not analyze.names_another(song, as_main):
        if as_main not in fits:
            alone, kind = as_main, "kept by album"
        elif len({bare(r) for r in candidates}) == 1:
            alone, kind = as_main, "one name"
        elif len({bare(r) for r in fits}) == 1:
            alone, kind = as_main, "one fit"
    if alone and (kind == "kept by album" or not chatgpt):
        return "open", alone
    return ("pick", maybe[0]) if chatgpt and maybe else ("nothing", None)


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
