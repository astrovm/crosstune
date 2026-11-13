#!/usr/bin/env python3
"""Replay the songs collect.py kept through the real ExactMatcher, without the network.

    python3 scripts/matchcheck/replay.py prepare OUT       # OUT/replay.json: what each song is served, and which results are right
    python3 scripts/matchcheck/replay.py run OUT LABEL      # MatchReplayTest in the Gradle box, in this checkout: OUT/replay-LABEL.json
    python3 scripts/matchcheck/replay.py table OUT LABEL [--against OTHER] [-v]

Each song is looked for on YouTube Music as a link from another service would ask for it: by its
US store name and artist. Apple's US search answers with its editions, Apple's lookups in the JP,
KR and TW stores with the names they give the same recording, Deezer with what it found, and YouTube
Music with the songs and videos it gave. Four ways:
1. "names": the JP, KR and TW names served; "latin": not, as when a store has no other name.
2. "alone": no ChatGPT; "asked": ChatGPT picks a right result when it's shown one, and none otherwise.

A song result is right when one of its names is a name a store gives the song, by its artist. A video
is right when it's named so, says no other version, and is the artist's or names the artist. Every
wrong open, and every video, is listed with -v, to be checked by eye.

Run LABEL on another checkout, e.g. origin/main, with the same test file and gradle-box.sh, and
compare with --against. OUT stays out of git: it holds what the services answered.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze  # noqa: E402 - the same reading of the answers
import collect  # noqa: E402 - the same cache, so nothing is asked again


# A name quoted or split off another way than title_names splits it: "Mr.Children 「しるし」 MUSIC VIDEO", "風のLONELY WAY/ Kiyotaka Sugiyama".
QUOTED = re.compile(r"[「」『』《》【】]|\s*/\s*")


def raw_path(out, url, body):
    """Where collect.fetch kept the answer to url and body; None when it never got one, so nothing is asked."""
    key = hashlib.sha256((url + json.dumps(body, sort_keys=True)).encode()).hexdigest()[:24]
    path = os.path.join(out, "raw", key + ".json")
    return path if os.path.exists(path) else None


def ytm_path(out, query, params):
    return raw_path(out, collect.YTM_SEARCH, {"context": {"client": collect.YTM_CLIENT}, "query": query, "params": params})


def right_ids(song, results, videos):
    """The videoIds of the song results and videos that are the song."""
    native = {analyze.bare(n["title"]) for n in song["native"]} | {analyze.normalize(n["title"]) for n in song["native"]}
    wanted = analyze.artists(song["artist"]) | {a for n in song["native"] for a in analyze.artists(n["artist"])}

    def by_eye(row):
        return (song["artist"], row["name"]) in analyze.RIGHT_BY_EYE

    def named(row):
        parts = analyze.title_names(row["name"]) + [p for p in QUOTED.split(row["name"]) if p.strip()]
        return any(
            analyze.bare(n) in native or analyze.normalize(n) in native or analyze.normalize(n) == analyze.normalize(song["title"])
            or analyze.bare(n) == analyze.bare(song["title"]) for n in parts)

    def says_another(row):
        # What the song's names say together is no other version: "(シングルver.)" for "(Single Version)", as the matcher has it.
        # The names the matcher is served: the first a store gave, as one lookup gives one.
        titles = [song["title"]] + list({n["store"]: n["title"] for n in reversed(song["native"])}.values())
        return analyze.says_another_version_of(row["name"], titles, song["artist"])

    # Checked by eye, it's the song, whatever its name says: "(Directors Edit Version)" for "(Director's Edit Version)".
    ids = {r["id"] for r in results if r["id"] and (by_eye(r) or named(r) and analyze.artists(r["details"][0]) & wanted and not says_another(r))}
    for v in videos:
        if not v["id"] or not named(v) or says_another(v):
            continue
        by = analyze.artists(v["details"][0]) & wanted or any(a in analyze.normalize(v["name"]) for a in wanted)
        if by:
            ids.add(v["id"])
    return sorted(ids)


def prepare(out):
    with open(os.path.join(out, "songs.json"), encoding="utf-8") as kept:
        songs = json.load(kept)
    replay = []
    for song in songs:
        query = song["query"]
        songs_path, videos_path = ytm_path(out, query, collect.YTM_SONGS), ytm_path(out, query, collect.YTM_VIDEOS)
        results = analyze.rows(json.load(open(songs_path, encoding="utf-8"))) if songs_path else []
        videos = analyze.rows(json.load(open(videos_path, encoding="utf-8"))) if videos_path else []
        names = {r["id"]: f"{r['name']} | {' • '.join(r['details'])}" for r in results + videos if r["id"]}
        native = {}
        for n in song["native"]:
            native.setdefault(n["store"], {"trackId": song["id"], "trackName": n["title"], "artistName": n["artist"], "collectionName": n["album"], "trackTimeMillis": song["lengthMs"]})
        replay.append({
            "title": song["title"], "artist": song["artist"],
            "us": [{"trackId": song["id"], "trackName": song["title"], "artistName": song["artist"], "collectionName": song["album"], "trackTimeMillis": ms}
                   for ms in (song["editionsMs"] or [song["lengthMs"]])],
            "native": native,
            "deezer": [{"title": d.get("title"), "artist": {"name": d.get("artist")}, "duration": d.get("duration")} for d in song.get("deezer", [])],
            "songs": songs_path, "videos": videos_path,
            "right": right_ids(song, results, videos), "names": names,
        })
    with open(os.path.join(out, "replay.json"), "w", encoding="utf-8") as kept:
        json.dump(replay, kept, ensure_ascii=False)
    print(f"{len(replay)} songs, {sum(1 for s in replay if s['right'])} with a right result", file=sys.stderr)


def run(out, label):
    """MatchReplayTest in the checkout this is run in, which may be another, e.g. origin/main's, with the same test file
    and gradle-box.sh; run again each time, as Gradle doesn't see that what it's served changed."""
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
    env = dict(os.environ, MATCHCHECK_DIR=os.path.abspath(out), MATCHCHECK_LABEL=label, GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
    subprocess.run(["nice", "-n", "10", os.path.join(root, "scripts/gradle-box/gradle-box.sh"), "--max-workers=2",
                    ":app:testDebugUnitTest", "--tests", "*MatchReplayTest", "--rerun"], cwd=root, env=env, check=True)


MODES = ("names, alone", "names, asked", "latin, alone", "latin, asked")


def verdicts(out, label):
    with open(os.path.join(out, "replay.json"), encoding="utf-8") as kept:
        replay = json.load(kept)
    with open(os.path.join(out, f"replay-{label}.json"), encoding="utf-8") as kept:
        found = json.load(kept)
    result = {}
    for mode in MODES:
        for index, song in enumerate(replay):
            url = found[mode][index]
            if url is None:
                verdict = "nothing"
            else:
                video = url.rsplit("v=", 1)[-1]
                verdict = "right" if video in song["right"] else "wrong"
            result[(mode, index)] = (verdict, song["names"].get(url.rsplit("v=", 1)[-1], url) if url else "")
    return replay, result


def table(out, label, against=None, verbose=False):
    replay, now = verdicts(out, label)
    shown = {name: json.load(open(os.path.join(out, f"replay-{name}.json"), encoding="utf-8")).get("shown", {}) for name in filter(None, (label, against))}
    other = verdicts(out, against)[1] if against else None
    for mode in MODES:
        counts = {}
        for index in range(len(replay)):
            key = (now[(mode, index)][0],) + ((other[(mode, index)][0],) if other else ())
            counts[key] = counts.get(key, 0) + 1
        print(f"\n{mode}, {len(replay)} songs\n")
        print(f"| {label} |" + (f" {against} |" if against else "") + " Songs |")
        print("|---|" + ("---|" if against else "") + "---|")
        for key, n in sorted(counts.items()):
            print("| " + " | ".join(key) + f" | {n} |")
        for index, song in enumerate(replay):
            verdict, row = now[(mode, index)]
            was = other[(mode, index)] if other else None
            if verbose and (verdict == "wrong" or was and was[0] != verdict):
                print(f"  {song['artist']} - {song['title']}: {label} {verdict} {row}" + (f"; {against} {was[0]} {was[1]}" if was else ""))
                for rows, picked in shown[label][mode][index]:
                    print(f"    shown {len(rows)}, picked {picked}: {rows[:3]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "run", "table"))
    parser.add_argument("out")
    parser.add_argument("label", nargs="?", default="head")
    parser.add_argument("--against")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.out)
    elif args.action == "run":
        run(args.out, args.label)
    else:
        table(args.out, args.label, args.against, args.verbose)


if __name__ == "__main__":
    main()
