"""
The songs ListenBrainz's listeners play most, per continent, for `songcheck.py songs --source listenbrainz`.

ListenBrainz only lists its top 1,000 recordings sitewide, and its top artists by country are
thin (Africa has one), so the list is built from what is open and unauthenticated:

1. Candidate artists: ListenBrainz's top artists, and every MusicBrainz artist with a tag, per country.
2. Each one's country (MusicBrainz, or ListenBrainz's lookup) puts it in a continent.
3. ListenBrainz's users per artist ranks them; the top ones in each continent get their recordings
   browsed on MusicBrainz, and ListenBrainz's users per recording ranks those. Users and not listens,
   as a few heavy fans make a song's listens.
4. One song per title and artist, best first, until a continent has as many as asked for with a
   30-second preview: ListenBrainz knows each recording's Apple Music id, and Apple's store has the
   preview and the length.

Every answer is kept on disk, so nothing is asked twice, and the services get the pace they ask for.
"""
import gzip
import hashlib
import json
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# MusicBrainz wants a contact in the User-Agent: the project's page, never an address.
USER_AGENT = "crosstune-songcheck (https://github.com/astrovm/crosstune)"
MUSICBRAINZ = "https://musicbrainz.org/ws/2"
LISTENBRAINZ = "https://api.listenbrainz.org/1"
LABS = "https://labs.api.listenbrainz.org"
APPLE = "https://itunes.apple.com/lookup"
# Seconds between two asks of the same service: MusicBrainz allows one a second, Apple about twenty a minute.
GAPS = {"musicbrainz.org": 1.1, "api.listenbrainz.org": 0.3, "labs.api.listenbrainz.org": 0.3, "itunes.apple.com": 3.1}
RETRY_CODES = {403, 429, 500, 502, 503, 504}
TRIES = 8
# MusicBrainz's search shows no more than this many results of one query.
SEARCH_MAX = 500
# Longer lists are cut by the first letter of the name, and no more than this many are taken from each, in no order of fame.
PER_LETTER = 200
LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"
# Recordings MusicBrainz lists for an artist, 100 a page; the most-played ones are among the first pages of a busy artist's.
RECORDING_PAGES = 5
UNNAMED = {"various artists", "unknown", "anonymous", "traditional", "no artist", "special purpose artist"}
# Where a country is, with the Americas and Europe split the way a map does. Turkey and Russia are Europe's, as in the Apple charts.
CONTINENTS = {
    "Africa": "DZ AO BJ BW BF BI CM CV CF TD KM CG CD CI DJ EG GQ ER SZ ET GA GM GH GN GW KE LS LR LY MG MW ML MR MU YT MA MZ NA NE NG RE RW ST SN SC SL SO ZA SS SD TZ TG TN UG EH ZM ZW SH",
    "Asia": "AF AM AZ BH BD BT BN KH CN CY GE HK IN ID IR IQ IL JP JO KZ KW KG LA LB MO MY MV MN MM NP KP OM PK PS PH QA SA SG KR LK SY TW TJ TH TL TM AE UZ VN YE",
    "Europe": "AL AD AT BY BE BA BG HR CZ DK EE FO FI FR DE GI GR GG VA HU IS IE IM IT JE XK LV LI LT LU MT MD MC ME NL MK NO PL PT RO RU SM RS SK SI ES SE CH UA GB TR AX SJ",
    "North America": "AG BS BB BZ CA CR CU DM DO SV GD GT HT HN JM MX NI PA KN LC VC TT US PR AW AI BM KY CW BQ BL GP GL MQ MS MF PM SX TC VG VI UM",
    "South America": "AR BO BR CL CO EC FK GF GY PY PE SR UY VE",
    "Oceania": "AU FJ KI MH FM NR NZ PW PG WS SB TO TV VU AS CK GU NC NF MP NU PF PN TK WF CX CC",
}
CONTINENT_OF = {code: continent for continent, codes in CONTINENTS.items() for code in codes.split()}
# Apple genres that aren't songs.
NOT_MUSIC = {"podcasts", "audiobooks", "spoken word", "comedy", "kids", "children's music", "fitness & workout", "sound effects", "meditation"}


def key(text):
    """Letters and digits only, lowercase: how two spellings of a title or an artist are told to be one."""
    return "".join(c for c in unicodedata.normalize("NFKC", text).casefold() if c.isalnum())


class Web:
    """JSON from the services, kept on disk under `cache`, at the pace each one asks for."""

    def __init__(self, cache, sleep=time.sleep, clock=time.monotonic, opener=urllib.request.urlopen):
        self.cache = Path(cache)
        self.sleep, self.clock, self.opener = sleep, clock, opener
        self.last = {}

    def json(self, url, body=None):
        digest = hashlib.sha1((url + (json.dumps(body, sort_keys=True) if body is not None else "")).encode()).hexdigest()
        path = self.cache / digest[:2] / f"{digest}.json.gz"
        if path.exists():
            return json.loads(gzip.decompress(path.read_bytes()))
        answer = self.fetch(url, body)
        path.parent.mkdir(parents=True, exist_ok=True)
        part = path.with_suffix(".part")
        part.write_bytes(gzip.compress(json.dumps(answer, ensure_ascii=False).encode()))
        part.replace(path)
        return answer

    def fetch(self, url, body):
        host = urllib.parse.urlparse(url).netloc
        for attempt in range(TRIES):
            self.sleep(max(0.0, GAPS.get(host, 1.0) - (self.clock() - self.last.get(host, -1e9))))
            self.last[host] = self.clock()
            request = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None, {"User-Agent": USER_AGENT, "Content-Type": "application/json"})
            try:
                with self.opener(request, timeout=120) as response:
                    return json.loads(response.read())
            except urllib.error.HTTPError as e:
                if e.code not in RETRY_CODES or attempt == TRIES - 1:
                    raise
            except OSError:
                if attempt == TRIES - 1:
                    raise
            # Busy: a longer wait each time.
            self.sleep(min(5 * 2 ** attempt, 120))


def batches(items, size):
    items = list(items)
    return [items[i:i + size] for i in range(0, len(items), size)]


def named(name):
    return bool(name) and not name.startswith("[") and name.casefold() not in UNNAMED


def countries(web, mbids):
    """Each artist's country code, from ListenBrainz's lookup, 100 at a time."""
    found = {}
    for batch in batches(mbids, 100):
        for item in web.json(f"{LABS}/artist-country-code-from-artist-mbid/json", [{"artist_mbid": mbid} for mbid in batch]):
            if item.get("country_code"):
                found[item["artist_mbid"]] = item["country_code"]
    return found


def top_artists(web):
    """ListenBrainz's most played artists, by country on the map and sitewide, whose country isn't known yet."""
    seeds = {}
    for country in web.json(f"{LISTENBRAINZ}/stats/sitewide/artist-map?range=year")["payload"]["artist_map"]:
        for artist in country["artists"]:
            seeds[artist["artist_mbid"]] = artist["artist_name"]
    for span in ("all_time", "year"):
        for artist in web.json(f"{LISTENBRAINZ}/stats/sitewide/artists?range={span}&count=1000")["payload"]["artists"]:
            if artist.get("artist_mbid"):
                seeds[artist["artist_mbid"]] = artist["artist_name"]
    return seeds


def search(web, query, cap):
    """Up to `cap` of a MusicBrainz artist search's results, and how many it has."""
    found, offset, count = {}, 0, 0
    while offset < min(cap, SEARCH_MAX):
        page = web.json(f"{MUSICBRAINZ}/artist?" + urllib.parse.urlencode({"query": query, "limit": 100, "offset": offset, "fmt": "json"}))
        count = page.get("count", 0)
        for artist in page.get("artists", []):
            found[artist["id"]] = artist["name"]
        offset += 100
        if offset >= count:
            break
    return found, count


def tagged_artists(web, country):
    """A country's MusicBrainz artists with a tag, which leaves out most of the unknown ones."""
    query = f"country:{country} AND tag:*"
    found, count = search(web, query, SEARCH_MAX)
    if count > SEARCH_MAX:
        for letter in LETTERS:
            found.update(search(web, f"{query} AND sortname:{letter}*", PER_LETTER)[0])
    return {mbid: {"name": name, "country": country} for mbid, name in found.items()}


def candidates(web):
    """Every artist worth ranking: MBID to name and country."""
    artists = {}
    for country in CONTINENT_OF:
        artists.update(tagged_artists(web, country))
        print(f"{country}: {len(artists)} artists so far")
    seeds = {mbid: name for mbid, name in top_artists(web).items() if mbid not in artists}
    for mbid, country in countries(web, seeds).items():
        artists[mbid] = {"name": seeds[mbid], "country": country}
    return {mbid: artist for mbid, artist in artists.items() if named(artist["name"]) and artist["country"] in CONTINENT_OF}


def users(web, what, mbids):
    """MBID to how many listeners and listens, in ListenBrainz."""
    found = {}
    for batch in batches(mbids, 1000):
        for item in web.json(f"{LISTENBRAINZ}/popularity/{what}", {f"{what}_mbids": batch}):
            found[item[f"{what}_mbid"]] = (item.get("total_user_count") or 0, item.get("total_listen_count") or 0)
    return found


def credit(recording):
    return "".join(part.get("name", "") + part.get("joinphrase", "") for part in recording.get("artist-credit", []))


def recordings(web, mbid):
    """An artist's recordings on MusicBrainz that aren't video."""
    found = []
    for page in range(RECORDING_PAGES):
        query = urllib.parse.urlencode({"artist": mbid, "limit": 100, "offset": page * 100, "inc": "artist-credits", "fmt": "json"})
        answer = web.json(f"{MUSICBRAINZ}/recording?{query}")
        found += [recording for recording in answer.get("recordings", []) if not recording.get("video")]
        if (page + 1) * 100 >= answer.get("recording-count", 0):
            break
    return found


def ranked(web, artists, per_continent):
    """Each continent's songs, most listeners first, one per title and artist: dicts with the recording's MBID."""
    rank = users(web, "artist", artists)
    chosen = {continent: [] for continent in CONTINENTS}
    for mbid, artist in artists.items():
        if rank.get(mbid, (0, 0))[0] > 0:
            chosen[CONTINENT_OF[artist["country"]]].append(mbid)
    songs = {continent: {} for continent in CONTINENTS}
    seen = set()
    for continent, mbids in chosen.items():
        mbids.sort(key=lambda mbid: rank[mbid], reverse=True)
        print(f"{continent}: {len(mbids)} artists with listeners, browsing the top {per_continent}")
        for number, artist in enumerate(mbids[:per_continent], 1):
            found = [recording for recording in recordings(web, artist) if recording["id"] not in seen]
            seen.update(recording["id"] for recording in found)
            heard = users(web, "recording", [recording["id"] for recording in found])
            for recording in found:
                listeners, listens = heard.get(recording["id"], (0, 0))
                song = key(recording["title"]), key(credit(recording))
                if listeners > 0 and (song not in songs[continent] or songs[continent][song]["users"] < listeners):
                    songs[continent][song] = {
                        "mbid": recording["id"], "title": recording["title"], "artist": credit(recording), "users": listeners, "listens": listens,
                        "country": artists[artist]["country"], "artistMbid": artist,
                    }
            if number % 25 == 0:
                print(f"  {number} artists browsed, {len(songs[continent])} songs")
    return {continent: sorted(found.values(), key=lambda song: (song["users"], song["listens"]), reverse=True) for continent, found in songs.items()}


def previews(web, songs):
    """Apple's entry, with its preview and length, for each song whose Apple Music id ListenBrainz knows."""
    ids = {}
    for batch in batches([song["mbid"] for song in songs], 1000):
        for item in web.json(f"{LABS}/apple-music-id-from-mbid/json", [{"recording_mbid": mbid} for mbid in batch]):
            ids[item["recording_mbid"]] = item.get("apple_music_track_ids") or []
    wanted = sorted({id for song in songs for id in ids.get(song["mbid"], [])})
    store = {}
    for batch in batches(wanted, 200):
        for item in web.json(f"{APPLE}?id={','.join(batch)}&country=us")["results"]:
            if item.get("kind") == "song" and item.get("previewUrl"):
                store[str(item["trackId"])] = item
    return {song["mbid"]: next((store[id] for id in ids.get(song["mbid"], []) if id in store), None) for song in songs}


def build(web, top, per_continent):
    """songs.json's list, and how many of each continent's best were tried to get `top` with a preview."""
    artists = candidates(web)
    found = ranked(web, artists, per_continent)
    listed, tried = [], {}
    taken = set()
    for continent, songs in found.items():
        rank = 0
        tried[continent] = 0
        for chunk in batches(songs, 200):
            if rank >= top:
                break
            apple = previews(web, chunk)
            for song in chunk:
                tried[continent] += 1
                item = apple[song["mbid"]]
                if item is None or str(item["trackId"]) in taken or rank >= top:
                    continue
                taken.add(str(item["trackId"]))
                rank += 1
                listed.append({
                    "id": str(item["trackId"]), "country": song["country"], "continent": continent, "rank": rank, "source": "listenbrainz",
                    "title": item["trackName"], "artist": item["artistName"], "lengthMs": item.get("trackTimeMillis"), "genre": item.get("primaryGenreName"),
                    "previewUrl": item["previewUrl"], "users": song["users"], "listens": song["listens"], "mbid": song["mbid"],
                })
        print(f"{continent}: {rank} songs with a preview from the best {tried[continent]} of {len(songs)}")
    # Best first across continents, so a run stopped halfway has checked all of them alike.
    order = list(CONTINENTS)
    listed.sort(key=lambda song: (song["rank"], order.index(song["continent"])))
    return listed, tried


ODD = re.compile(r"podcast|audiobook|episode|chapter \d|interview|karaoke|sped up|slowed|nightcore|8d audio|lullab|white noise|rain sounds", re.I)


def describe(songs, tried):
    """Markdown on a list: per continent how many, the top 10, and what looks odd."""
    lines = []
    for continent in CONTINENTS:
        found = [song for song in songs if song["continent"] == continent]
        genres = {}
        for song in found:
            genres[song["genre"]] = genres.get(song["genre"], 0) + 1
        countries = {}
        for song in found:
            countries[song["country"]] = countries.get(song["country"], 0) + 1
        odd = [song for song in found if (song["genre"] or "").casefold() in NOT_MUSIC or ODD.search(song["title"])]
        lines += [
            f"### {continent}", "",
            f"- {len(found)} songs with a preview, from the best {tried.get(continent, 0)} ranked",
            f"- Artists' countries: {', '.join(f'{code} {count}' for code, count in sorted(countries.items(), key=lambda item: -item[1])[:8])}",
            f"- Genres: {', '.join(f'{genre} {count}' for genre, count in sorted(genres.items(), key=lambda item: -item[1])[:6])}",
            f"- Odd (not music, or a sped-up or slowed copy): {len(odd)}" + "".join(f"; {song['artist']} - {song['title']}" for song in odd[:3]),
            "",
        ]
        lines += [f"{number}. {song['artist']} - {song['title']} ({song['users']} listeners)" for number, song in enumerate(found[:10], 1)]
        lines.append("")
    return "\n".join(lines)
