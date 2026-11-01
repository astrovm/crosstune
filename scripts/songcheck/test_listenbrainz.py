import io
import json
import sys
import tempfile
import types
import unittest
import urllib.error
from pathlib import Path

sys.modules.setdefault("anyascii", types.SimpleNamespace(anyascii=lambda text: text))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import listenbrainz  # noqa: E402
import songcheck  # noqa: E402


class FakeServices(listenbrainz.Web):
    """The services' answers, in the shapes they have, for a few made-up artists and songs."""

    RECORDINGS = {
        "a-us": [
            {"id": "r-us-1", "title": "Big Hit", "artist-credit": [{"name": "Fake Band", "joinphrase": " feat. "}, {"name": "Guest", "joinphrase": ""}]},
            {"id": "r-us-2", "title": "Big Hit", "disambiguation": "live", "artist-credit": [{"name": "Fake Band", "joinphrase": " feat. "}, {"name": "Guest", "joinphrase": ""}]},
            {"id": "r-us-3", "title": "Clip", "video": True, "artist-credit": [{"name": "Fake Band"}]},
            {"id": "r-us-4", "title": "No Preview", "artist-credit": [{"name": "Fake Band"}]},
            {"id": "r-us-5", "title": "Heavy Fans", "artist-credit": [{"name": "Fake Band"}]},
        ],
        "a-ng": [{"id": "r-ng-1", "title": "Lagos Night", "artist-credit": [{"name": "Naija Star"}]}],
        "a-zz": [{"id": "r-zz-1", "title": "Nobody", "artist-credit": [{"name": "Nobody"}]}],
    }
    USERS = {"r-us-1": (900, 1_000), "r-us-2": (40, 50), "r-us-4": (500, 600), "r-us-5": (30, 9_000_000), "r-ng-1": (70, 80), "r-zz-1": (0, 0)}
    APPLE_IDS = {"r-us-1": ["101", "102"], "r-us-2": ["103"], "r-us-5": ["105"], "r-ng-1": ["201"]}

    def __init__(self):
        super().__init__(tempfile.mkdtemp())
        self.asked = []

    def json(self, url, body=None):
        self.asked.append(url)
        if "artist-map" in url:
            return {"payload": {"artist_map": [{"country": "USA", "artists": [{"artist_mbid": "a-us", "artist_name": "Fake Band", "listen_count": 5}]}]}}
        if "sitewide/artists" in url:
            return {"payload": {"artists": [{"artist_mbid": "a-us", "artist_name": "Fake Band", "listen_count": 9}, {"artist_mbid": "a-zz", "artist_name": "Nobody", "listen_count": 1}]}}
        if "musicbrainz.org/ws/2/artist" in url:
            ng = "country%3ANG" in url
            return {"count": 2 if ng else 0, "artists": [{"id": "a-ng", "name": "Naija Star"}, {"id": "a-un", "name": "[unknown]"}][:2 if ng else 0]}
        if "artist-country-code" in url:
            return [{"artist_mbid": item["artist_mbid"], "country_code": {"a-us": "US"}.get(item["artist_mbid"])} for item in body]
        if "popularity/artist" in url:
            return [{"artist_mbid": mbid, "total_user_count": {"a-us": 5000, "a-ng": 300, "a-zz": None}[mbid], "total_listen_count": 1} for mbid in body["artist_mbids"]]
        if "ws/2/recording" in url:
            artist = url.split("artist=")[1].split("&")[0]
            return {"recording-count": len(self.RECORDINGS[artist]), "recordings": self.RECORDINGS[artist]}
        if "popularity/recording" in url:
            return [{"recording_mbid": mbid, "total_user_count": self.USERS.get(mbid, (None, None))[0], "total_listen_count": self.USERS.get(mbid, (None, None))[1]} for mbid in body["recording_mbids"]]
        if "apple-music-id-from-mbid" in url:
            return [{"recording_mbid": item["recording_mbid"], "apple_music_track_ids": self.APPLE_IDS.get(item["recording_mbid"], [])} for item in body]
        if "itunes.apple.com" in url:
            ids = url.split("id=")[1].split("&")[0].split(",")
            store = {
                "102": {"trackId": 102, "kind": "song", "trackName": "Big Hit", "artistName": "Fake Band & Guest", "trackTimeMillis": 200_000, "primaryGenreName": "Pop", "previewUrl": "https://example.com/102.m4a"},
                "105": {"trackId": 105, "kind": "song", "trackName": "Heavy Fans", "artistName": "Fake Band", "trackTimeMillis": 180_000, "primaryGenreName": "Pop", "previewUrl": "https://example.com/105.m4a"},
                "201": {"trackId": 201, "kind": "song", "trackName": "Lagos Night", "artistName": "Naija Star", "trackTimeMillis": 190_000, "primaryGenreName": "Afrobeats", "previewUrl": "https://example.com/201.m4a"},
                # Apple has "101" as a podcast without a preview, and "103" as a song without one.
                "101": {"trackId": 101, "kind": "podcast-episode", "previewUrl": "https://example.com/101.m4a"},
                "103": {"trackId": 103, "kind": "song", "trackName": "Big Hit"},
            }
            return {"results": [store[id] for id in ids if id in store]}
        raise AssertionError(url)


class ContinentsTest(unittest.TestCase):
    def test_a_country_is_in_one_continent(self):
        self.assertEqual(sum(len(codes.split()) for codes in listenbrainz.CONTINENTS.values()), len(listenbrainz.CONTINENT_OF))

    def test_the_continents_the_charts_use_agree(self):
        for country, continent in songcheck.CONTINENTS.items():
            self.assertEqual(continent, listenbrainz.CONTINENT_OF[country.upper()], country)


class WebTest(unittest.TestCase):
    def web(self, *answers):
        slept, asked = [], []
        queue = list(answers)

        def opener(request, timeout):
            asked.append(request)
            answer = queue.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return io.BytesIO(json.dumps(answer).encode())

        now = [0.0]
        web = listenbrainz.Web(tempfile.mkdtemp(), sleep=lambda seconds: (slept.append(seconds), now.__setitem__(0, now[0] + seconds)), clock=lambda: now[0], opener=opener)
        return web, slept, asked

    def test_an_answer_is_asked_once_and_sent_with_the_project_as_contact(self):
        web, _, asked = self.web({"a": 1})
        self.assertEqual({"a": 1}, web.json("https://musicbrainz.org/ws/2/artist?x=1"))
        self.assertEqual({"a": 1}, web.json("https://musicbrainz.org/ws/2/artist?x=1"))
        self.assertEqual(1, len(asked))
        self.assertEqual("crosstune-songcheck (https://github.com/astrovm/crosstune)", asked[0].get_header("User-agent"))
        self.assertNotIn("@", asked[0].get_header("User-agent"))

    def test_the_same_question_with_another_body_is_another_answer(self):
        web, _, asked = self.web([1], [2])
        self.assertEqual([1], web.json("https://api.listenbrainz.org/1/popularity/artist", {"artist_mbids": ["a"]}))
        self.assertEqual([2], web.json("https://api.listenbrainz.org/1/popularity/artist", {"artist_mbids": ["b"]}))
        self.assertEqual(2, len(asked))

    def test_musicbrainz_is_asked_once_a_second_at_most(self):
        web, slept, _ = self.web({}, {})
        web.json("https://musicbrainz.org/ws/2/artist?x=1")
        web.json("https://musicbrainz.org/ws/2/artist?x=2")
        self.assertGreaterEqual(sum(slept), 1.0)

    def test_a_busy_service_is_asked_again_later(self):
        busy = urllib.error.HTTPError("https://musicbrainz.org", 503, "busy", {}, None)
        web, slept, asked = self.web(busy, OSError("timed out"), {"ok": True})
        self.assertEqual({"ok": True}, web.json("https://musicbrainz.org/ws/2/artist?x=1"))
        self.assertEqual(3, len(asked))
        self.assertGreaterEqual(sum(slept), 15)

    def test_a_bad_question_is_not_asked_again(self):
        bad = urllib.error.HTTPError("https://api.listenbrainz.org", 400, "bad", {}, None)
        web, _, asked = self.web(bad)
        with self.assertRaises(urllib.error.HTTPError):
            web.json("https://api.listenbrainz.org/1/popularity/artist", {})
        self.assertEqual(1, len(asked))

    def test_it_gives_up_when_the_service_stays_busy(self):
        busy = urllib.error.HTTPError("https://musicbrainz.org", 503, "busy", {}, None)
        web, _, asked = self.web(*[busy] * listenbrainz.TRIES)
        with self.assertRaises(urllib.error.HTTPError):
            web.json("https://musicbrainz.org/ws/2/artist?x=1")
        self.assertEqual(listenbrainz.TRIES, len(asked))


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.web = FakeServices()
        self.songs, self.tried = listenbrainz.build(self.web, top=10, per_continent=10)

    def titles(self, continent):
        return [(song["rank"], song["title"]) for song in self.songs if song["continent"] == continent]

    def test_songs_are_ranked_by_listeners_not_listens(self):
        self.assertEqual([(1, "Big Hit"), (2, "Heavy Fans")], self.titles("North America"))

    def test_a_song_is_once_with_the_apple_track_that_has_a_preview(self):
        big = next(song for song in self.songs if song["title"] == "Big Hit")
        self.assertEqual(("102", "Fake Band & Guest", 200_000, 900), (big["id"], big["artist"], big["lengthMs"], big["users"]))
        self.assertEqual("https://example.com/102.m4a", big["previewUrl"])

    def test_a_continent_comes_from_the_artists_country(self):
        self.assertEqual([(1, "Lagos Night")], self.titles("Africa"))
        self.assertEqual("NG", next(song for song in self.songs if song["continent"] == "Africa")["country"])

    def test_artists_nobody_listens_to_are_left_out(self):
        self.assertFalse([song for song in self.songs if song["title"] == "Nobody"])

    def test_the_best_of_each_continent_come_first_so_half_a_run_covers_all(self):
        self.assertEqual([("Africa", 1), ("North America", 1), ("North America", 2)], [(song["continent"], song["rank"]) for song in self.songs])

    def test_it_says_how_many_were_tried_per_continent(self):
        self.assertEqual({"North America": 3, "Africa": 1}, {continent: count for continent, count in self.tried.items() if count})

    def test_it_stops_at_the_top_asked_for(self):
        songs, _ = listenbrainz.build(self.web, top=1, per_continent=10)
        self.assertEqual(["Lagos Night", "Big Hit"], [song["title"] for song in songs])

    def test_only_the_artists_per_continent_asked_for_are_browsed(self):
        web = FakeServices()
        listenbrainz.build(web, top=10, per_continent=0)
        self.assertFalse([url for url in web.asked if "ws/2/recording" in url])

    def test_the_description_has_what_a_report_needs(self):
        text = listenbrainz.describe(self.songs, self.tried)
        self.assertIn("### North America", text)
        self.assertIn("2 songs with a preview, from the best 3 ranked", text)
        self.assertIn("1. Fake Band & Guest - Big Hit (900 listeners)", text)
        self.assertIn("Odd (not music, or a sped-up or slowed copy): 0", text)

    def test_it_points_out_what_is_not_music(self):
        songs = [{**self.songs[0], "genre": "Podcasts"}, {**self.songs[0], "title": "Big Hit (Sped Up)"}, self.songs[0]]
        self.assertIn("Odd (not music, or a sped-up or slowed copy): 2", listenbrainz.describe(songs, {}))


class SummaryTest(unittest.TestCase):
    def test_a_continents_list_is_one_group_whatever_its_artists_countries(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            (out / "reports").mkdir()
            for id, country in (("1", "NG"), ("2", "ZA")):
                (out / "reports" / f"{id}.json").write_text(json.dumps({
                    "id": id, "country": country, "continent": "Africa", "source": "listenbrainz", "rank": int(id), "title": "T", "artist": "A",
                    "lyrics": {"kind": "found", "lines": []},
                }))
            (out / "reports" / "3.json").write_text(json.dumps({
                "id": "3", "country": "ng", "continent": "Africa", "rank": 3, "title": "T", "artist": "A", "lyrics": {"kind": "notfound"},
            }))
            songcheck.OUT = out
            songcheck.summary(None)
            text = (out / "summary.md").read_text()
        self.assertIn("## Africa\n", text)
        self.assertIn("- Lyrics: 2 of 2 found", text)
        self.assertIn("## Africa (ng)", text)
        self.assertIn("- Lyrics: 0 of 1 found", text)


if __name__ == "__main__":
    unittest.main()
