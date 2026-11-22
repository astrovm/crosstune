#!/usr/bin/env python3
"""Replay the songs collect.py kept through the real ExactMatcher, without the network.

    python3 scripts/matchcheck/replay.py prepare OUT --require-answers  # fail if a search wasn't captured
    python3 scripts/matchcheck/replay.py run OUT LABEL [--apple-down | --both]  # --both writes LABEL-up and LABEL-down
    python3 scripts/matchcheck/replay.py table OUT LABEL [--against OTHER] [-v]

Each song is looked for on YouTube Music as a link from another service would ask for it: by its
US store name and artist. Apple's US search answers with its editions, Apple's lookups in the JP,
KR and TW stores with the names they give the same recording, Deezer with what it found, and YouTube
Music with the songs and videos it gave. Four ways:
1. "names": the JP, KR and TW names served; "latin": not, as when a store has no other name.
2. "alone": no ChatGPT; "asked": ChatGPT picks a right result when it's shown one, and none otherwise.

A collected length is passed to the matcher. With --apple-down every Apple request fails.
A song result is right when one of its names is a name a store gives the song, by its artist. A video
is right when it's named so, says no other version, and is the artist's; or names the artist, where
no song result and no video of the artist's is right, as someone else's lyrics video is no match where
the song is there. Every wrong open, and every video, is listed with -v, to be checked by eye.

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
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze
import collect

# A name quoted or split off another way than title_names splits it: "Mr.Children 「しるし」 MUSIC VIDEO", "風のLONELY WAY/ Kiyotaka Sugiyama".
QUOTED = re.compile(r"[「」『』《》【】]|\s*/\s*")


def raw_path(out, url, body):
    """Where collect.fetch kept the answer to url and body; None when it never got one, so nothing is asked."""
    key = hashlib.sha256((url + json.dumps(body, sort_keys=True)).encode()).hexdigest()[:24]
    path = os.path.join(out, "raw", key + ".json")
    return path if os.path.exists(path) else None


def ytm_path(out, query, params):
    return raw_path(out, collect.YTM_SEARCH, {"context": {"client": collect.YTM_CLIENT}, "query": query, "params": params})


# What YouTube adds to an artist's own channel's name, as ExactMatcher's CHANNEL_SUFFIXES: "Band - Topic", "BandVEVO".
CHANNEL_SUFFIXES = {"topic", "vevo", "official", "music", "tv"}
# Qobuz names the same artist both ways on this album: https://www.qobuz.com/us-en/album/kifak-inta-fairuz/mx9y215fx1z8a
ARTIST_ALIASES = {"fairouz": {"fairuz"}, "fairuz": {"fairouz"}}
# SongNames' leadingArticle, artistSeparator and conjunction, which split a credit into its artists.
LEADING_ARTICLE = re.compile(r"^the\s+", re.IGNORECASE)
CREDIT_SEPARATOR = re.compile(r"\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*", re.IGNORECASE)
CONJUNCTION = re.compile(r"\s+(?:and|y|und|et)\s+", re.IGNORECASE)


def normalized(text):
    """SongNames.normalize: NFD, its marks dropped, lowercase, letters and digits only. Not analyze.normalize, whose NFKD
    makes a fullwidth ＹＯＡＳＯＢＩ YOASOBI, where the app keeps them apart."""
    folded = "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.category(c).startswith("M"))
    return "".join(c for c in folded.lower() if unicodedata.category(c)[0] in "LN")


def credited(credit):
    """SongNames.artists: each artist in a credit, and the whole credit, without the article, normalized."""
    names = [part for name in CREDIT_SEPARATOR.split(credit) if name.strip() for part in CONJUNCTION.split(name.strip())]
    return {normalized(LEADING_ARTICLE.sub("", name)) for name in [*names, credit]} - {""}


def own_channel(uploader, credits):
    """Whether a video's channel is the artist's own, as ExactMatcher.isOwnVideo tells: the whole channel name, without its
    article, is one of the artists in [credits], maybe with what YouTube adds to it."""
    channel = normalized(LEADING_ARTICLE.sub("", uploader))
    names = {name for credit in credits for name in credited(credit)}
    return any(channel == a or (channel.startswith(a) and channel[len(a):] in CHANNEL_SUFFIXES) for a in names)


def right_ids(song, results, videos):
    """The videoIds of the song results and videos that are the song."""
    native = {analyze.bare(n["title"]) for n in song["native"]} | {analyze.normalize(n["title"]) for n in song["native"]}
    wanted = analyze.artists(song["artist"]) | {a for n in song["native"] for a in analyze.artists(n["artist"])}
    wanted |= {alias for artist in wanted for alias in ARTIST_ALIASES.get(artist, set())}

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
    ids = {r["id"] for r in results if r["id"] and (by_eye(r) or (named(r) and analyze.artists(r["details"][0]) & wanted and not says_another(r)))}
    named_only = set()
    for v in videos:
        if not v["id"] or not named(v) or says_another(v):
            continue
        if own_channel(v["details"][0], [song["artist"]] + [n["artist"] for n in song["native"]] + list(wanted)):
            ids.add(v["id"])
        elif any(a in analyze.normalize(v["name"]) for a in wanted):
            named_only.add(v["id"])
    # A video someone else put up that only names the artist, e.g. a fan's lyrics video, is the song only where YouTube
    # Music has nothing of the artist's own: not where it has the song itself, or the artist's video of it.
    return sorted(ids or named_only)


def read_json(path):
    with open(path, encoding="utf-8") as kept:
        return json.load(kept)


def prepare(out, require_answers=False):
    with open(os.path.join(out, "songs.json"), encoding="utf-8") as kept:
        songs = json.load(kept)
    replay = []
    for song in songs:
        query = song["query"]
        songs_path, videos_path = ytm_path(out, query, collect.YTM_SONGS), ytm_path(out, query, collect.YTM_VIDEOS)
        if require_answers and (not songs_path or not videos_path or not song.get("ytmSongs") or not song.get("ytmVideos") or not song.get("deezerAnswered") or not song.get("deezerArtistsAnswered")):
            raise ValueError(f"Missing YouTube Music or Deezer answer for {query}")
        results = analyze.rows(read_json(songs_path)) if songs_path else []
        videos = analyze.rows(read_json(videos_path)) if videos_path else []
        names = {r["id"]: f"{r['name']} | {' • '.join(r['details'])}" for r in results + videos if r["id"]}
        native = {}
        for n in song["native"]:
            native.setdefault(n["store"], {"trackId": song["id"], "trackName": n["title"], "artistName": n["artist"], "collectionName": n["album"], "trackTimeMillis": song["lengthMs"]})
        replay.append({
            "title": song["title"], "artist": song.get("askedArtist", song["artist"]), "lengthMs": song["lengthMs"], "query": query,
            "us": [{"trackId": song["id"], "trackName": song["title"], "artistName": song["artist"], "collectionName": song["album"], "trackTimeMillis": ms}
                   for ms in (song["editionsMs"] or [song["lengthMs"]])],
            "native": native,
            "deezer": song.get("deezer", []), "deezerArtists": song.get("deezerArtists", []),
            # Where in out, so a copy of the folder, as another task's or the build box's, still finds them.
            "songs": songs_path and os.path.relpath(songs_path, out), "videos": videos_path and os.path.relpath(videos_path, out),
            "right": right_ids(song, results, videos), "names": names,
        })
    with open(os.path.join(out, "replay.json"), "w", encoding="utf-8") as kept:
        json.dump(replay, kept, ensure_ascii=False)
    print(f"{len(replay)} songs, {sum(1 for s in replay if s['right'])} with a right result", file=sys.stderr)


def run(out, label, apple_down=False, both=False):
    """MatchReplayTest in the checkout this is run in, which may be another, e.g. origin/main's, with the same test file
    and gradle-box.sh; run again each time, as Gradle doesn't see that what it's served changed."""
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
    env = dict(os.environ, MATCHCHECK_DIR=os.path.abspath(out), MATCHCHECK_LABEL=label, MATCHCHECK_APPLE_DOWN="1" if apple_down else "0", MATCHCHECK_BOTH="1" if both else "0", GRADLE_BOX_MEMORY=os.environ.get("GRADLE_BOX_MEMORY", "7"))
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
    shown = {name: read_json(os.path.join(out, f"replay-{name}.json")).get("shown", {}) for name in filter(None, (label, against))}
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
            if verbose and (verdict == "wrong" or (was and was[0] != verdict)):
                print(f"  {song['artist']} - {song['title']}: {label} {verdict} {row}" + (f"; {against} {was[0]} {was[1]}" if was else ""))
                for rows, picked in shown[label][mode][index]:
                    print(f"    shown {len(rows)}, picked {picked}: {rows[:3]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "run", "table"))
    parser.add_argument("out")
    parser.add_argument("label", nargs="?", default="head")
    parser.add_argument("--against")
    parser.add_argument("--apple-down", action="store_true", help="make Apple requests fail")
    parser.add_argument("--both", action="store_true", help="write LABEL-up and LABEL-down in one run")
    parser.add_argument("--require-answers", action="store_true", help="fail preparation if a YouTube Music answer is missing")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.out, args.require_answers)
    elif args.action == "run":
        run(args.out, args.label, args.apple_down, args.both)
    else:
        table(args.out, args.label, args.against, args.verbose)


if __name__ == "__main__":
    main()
