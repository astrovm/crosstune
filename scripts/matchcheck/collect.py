#!/usr/bin/env python3
"""Collect real data to replay the YouTube Music matcher on (replay.py), read-only.

For each artist in ARTISTS, Apple's US store gives songs with Latin names, as a link from
another service would. Apple's JP, KR and TW stores give the same recordings under their own
names, found by the same length to the millisecond. For each song, YouTube Music's song and
video search and Deezer's search are asked as the app asks them. Every answer is kept in
OUT/raw, so replay.py can serve it again without the network.

    python3 scripts/matchcheck/collect.py OUT [--per-artist N]

Nothing is sent but searches by an artist's or a song's name.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ARTISTS = [
    # Japanese
    "Yoko Takahashi", "Miki Matsubara", "Mariya Takeuchi", "Anri", "Tatsuro Yamashita", "Taeko Ohnuki",
    "Hikaru Utada", "LiSA", "Aimer", "YOASOBI", "Kenshi Yonezu", "RADWIMPS", "Spitz", "The Blue Hearts",
    "Yumi Matsutoya", "Momoe Yamaguchi", "Akina Nakamori", "Seiko Matsuda", "Megumi Hayashibara",
    "Mr.Children", "Southern All Stars", "Masaki Suda", "Eve", "ASIAN KUNG-FU GENERATION", "Kalafina",
    "ClariS", "Junko Yagami", "Hiromi Iwasaki", "Kenji Ozawa", "Hiroshi Sato", "Eiichi Ohtaki",
    "Yellow Magic Orchestra", "Tomoko Aran", "Toshiki Kadomatsu", "Yasuha", "Kiyotaka Sugiyama",
    # Korean
    "Kim Kwang Seok", "Lee Moon Sae", "Sanulrim", "Cho Yong Pil", "Akdong Musician", "10cm",
    "Busker Busker", "Kim Hyun Chul", "Yoo Jae Ha", "Shin Seung Hun", "Lee Sun Hee",
    # Chinese
    "Teresa Teng", "Faye Wong", "Leslie Cheung", "Beyond", "Jacky Cheung", "Eason Chan", "Jay Chou",
    "Anita Mui", "Sally Yeh",
    # Other scripts
    "Kino", "Alla Pugacheva", "Fairuz", "Bird Thongchai", "Haris Alexiou", "Ofra Haza",
]

YTM_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
YTM_SONGS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="
YTM_VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
YTM_CLIENT = {"clientName": "WEB_REMIX", "clientVersion": "1.20240101.01.00", "hl": "en"}
STORES = ["JP", "KR", "TW"]


def fetch(out, url, body=None, pause=0.0):
    """The answer to url, kept in out/raw by what was asked, so it's asked only once."""
    key = hashlib.sha256((url + json.dumps(body, sort_keys=True)).encode()).hexdigest()[:24]
    path = os.path.join(out, "raw", key + ".json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as kept:
            return json.load(kept)
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                answer = json.load(response)
            break
        except Exception as error:  # noqa: BLE001 - any failure is retried, then reported
            print(f"  retry {attempt + 1} {url[:80]}: {error}", file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    else:
        return None
    with open(path, "w", encoding="utf-8") as kept:
        json.dump(answer, kept, ensure_ascii=False)
    time.sleep(pause)
    return answer


def apple_search(out, term, store):
    query = urllib.parse.urlencode({"term": term, "entity": "song", "limit": 50, "country": store})
    return (fetch(out, "https://itunes.apple.com/search?" + query, pause=3.5) or {}).get("results", [])


def ytm(out, query, params):
    return fetch(out, YTM_SEARCH, {"context": {"client": YTM_CLIENT}, "query": query, "params": params}, pause=0.5)


def deezer(out, query):
    """Deezer's track search, asked as the app asks it: "title artist", ten results."""
    asked = urllib.parse.urlencode({"q": query, "limit": 10})
    return (fetch(out, "https://api.deezer.com/search/track?" + asked, pause=0.3) or {}).get("data", [])


def latin(text):
    return all(ord(c) < 0x250 or not c.isalpha() for c in text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--per-artist", type=int, default=4)
    args = parser.parse_args()
    os.makedirs(os.path.join(args.out, "raw"), exist_ok=True)
    songs = []
    for artist in ARTISTS:
        us = [r for r in apple_search(args.out, artist, "US") if r.get("kind") == "song"]
        native = {}
        for store in STORES:
            for r in apple_search(args.out, artist, store):
                if r.get("kind") == "song" and not latin(r.get("trackName", "")):
                    native.setdefault(r["trackTimeMillis"], []).append({"store": store, "title": r["trackName"], "artist": r["artistName"], "album": r.get("collectionName", "")})
        picked = []
        for r in us:
            if not latin(r["trackName"]) or r["trackTimeMillis"] not in native:
                continue
            if any(p["title"] == r["trackName"] for p in picked):
                continue
            picked.append({
                "id": r["trackId"], "title": r["trackName"], "artist": r["artistName"], "album": r.get("collectionName", ""),
                "lengthMs": r["trackTimeMillis"], "native": native[r["trackTimeMillis"]],
                "editionsMs": sorted({o["trackTimeMillis"] for o in us if o["trackName"] == r["trackName"] and o.get("trackTimeMillis")}),
            })
            if len(picked) >= args.per_artist:
                break
        print(f"{artist}: {len(us)} in US, {len(native)} native lengths, {len(picked)} picked", file=sys.stderr)
        for song in picked:
            query = f"{song['title']} {song['artist']}"
            song["ytmSongs"] = ytm(args.out, query, YTM_SONGS) is not None
            song["ytmVideos"] = ytm(args.out, query, YTM_VIDEOS) is not None
            song["deezer"] = [{"title": d.get("title"), "artist": d.get("artist", {}).get("name"), "duration": d.get("duration")} for d in deezer(args.out, query)]
            song["query"] = query
            songs.append(song)
    with open(os.path.join(args.out, "songs.json"), "w", encoding="utf-8") as kept:
        json.dump(songs, kept, ensure_ascii=False, indent=1)
    print(f"{len(songs)} songs", file=sys.stderr)


if __name__ == "__main__":
    main()
