import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import replay

# Lemon, as Apple's US store names it, and Japan's; with rows YouTube Music gave for "Lemon 米津玄師" (issue #1103).
LEMON = {"title": "Lemon", "artist": "Kenshi Yonezu", "native": [{"store": "JP", "title": "Lemon", "artist": "米津玄師", "album": "Lemon - Single"}]}
SONG = {"id": "3NNhrqHZqlI", "name": "Lemon", "details": ["Kenshi Yonezu", "Lemon", "4:17"]}
OWN_VIDEO = {"id": "SX_ViT4Ra7k", "name": "Lemon", "details": ["Kenshi Yonezu", "1B views", "4:35"]}
LYRICS_VIDEO = {"id": "Kl5G4bAwpOg", "name": "Lemon - 米津玄師｜歌詞あり", "details": ["J -KARAOKE", "3.7M views", "4:20"]}


class RightIdsTest(unittest.TestCase):
    def test_a_fans_lyrics_video_is_wrong_where_the_song_is_there(self):
        self.assertEqual(["3NNhrqHZqlI", "SX_ViT4Ra7k"], replay.right_ids(LEMON, [SONG], [OWN_VIDEO, LYRICS_VIDEO]))

    def test_a_fans_lyrics_video_is_wrong_where_the_artists_video_is_there(self):
        self.assertEqual(["SX_ViT4Ra7k"], replay.right_ids(LEMON, [], [OWN_VIDEO, LYRICS_VIDEO]))

    def test_a_video_naming_the_artist_is_right_where_nothing_of_theirs_is(self):
        self.assertEqual(["Kl5G4bAwpOg"], replay.right_ids(LEMON, [], [LYRICS_VIDEO]))

    def test_the_artists_channel_counts_with_what_youtube_adds_to_its_name(self):
        for channel in ("Kenshi Yonezu - Topic", "KenshiYonezuVEVO", "Kenshi Yonezu Official"):
            video = dict(OWN_VIDEO, details=[channel, "1B views", "4:35"])
            self.assertEqual(["SX_ViT4Ra7k"], replay.right_ids(LEMON, [], [video, LYRICS_VIDEO]), channel)

    def test_a_channel_only_starting_with_the_artists_name_is_someone_elses(self):
        video = dict(LYRICS_VIDEO, details=["Kenshi Yonezu Fan Club", "3.7M views", "4:20"], name="Lemon - Kenshi Yonezu")
        self.assertEqual(["3NNhrqHZqlI"], replay.right_ids(LEMON, [SONG], [video]))

    def test_the_band_counts_without_its_article(self):
        song = {"title": "Song", "artist": "The Band", "native": []}
        video = {"id": "own00000000", "name": "Song", "details": ["Band VEVO", "1M views", "3:00"]}
        self.assertEqual(["own00000000"], replay.right_ids(song, [], [video]))

    def test_a_channel_is_one_artist_or_the_whole_credit_never_a_credit_naming_one(self):
        for channel, own in (("Band A & Band B", True), ("Band B - Topic", True), ("Band A & Someone", False), ("Someone x Band A", False)):
            self.assertEqual(own, replay.own_channel(channel, ["Band A & Band B"]), channel)

    def test_a_channel_is_named_as_the_app_normalizes_names(self):
        # SongNames.normalize drops accents but keeps fullwidth letters apart, as NFD does.
        for channel, own in (("YOASOBI", True), ("Yoasobi - Topic", True), ("ＹＯＡＳＯＢＩ", False)):
            self.assertEqual(own, replay.own_channel(channel, ["YOASOBI"]), channel)
        self.assertTrue(replay.own_channel("Beyoncé", ["Beyonce"]))


class PrepareTest(unittest.TestCase):
    def test_the_answers_kept_are_named_where_in_the_folder_so_a_copy_of_it_finds_them(self):
        with tempfile.TemporaryDirectory() as out:
            song = dict(LEMON, id=1, album="Lemon - Single", lengthMs=255_000, editionsMs=[], query="Lemon 米津玄師", askedArtist="米津玄師", ytmSongs=True, ytmVideos=True, deezerAnswered=True)
            with open(os.path.join(out, "songs.json"), "w", encoding="utf-8") as kept:
                json.dump([song], kept)
            os.mkdir(os.path.join(out, "raw"))
            songs = replay.ytm_path(out, song["query"], replay.collect.YTM_SONGS)
            self.assertIsNone(songs)
            for params in (replay.collect.YTM_SONGS, replay.collect.YTM_VIDEOS):
                body = {"context": {"client": replay.collect.YTM_CLIENT}, "query": song["query"], "params": params}
                key = hashlib.sha256((replay.collect.YTM_SEARCH + json.dumps(body, sort_keys=True)).encode()).hexdigest()[:24]
                with open(os.path.join(out, "raw", key + ".json"), "w", encoding="utf-8") as kept:
                    json.dump({}, kept)
            with contextlib.redirect_stderr(io.StringIO()):
                replay.prepare(out, require_answers=True)
            with open(os.path.join(out, "replay.json"), encoding="utf-8") as kept:
                [prepared] = json.load(kept)
            for name in ("songs", "videos"):
                self.assertFalse(os.path.isabs(prepared[name]))
                self.assertTrue(prepared[name].startswith("raw" + os.sep))
                self.assertTrue(os.path.isfile(os.path.join(out, prepared[name])))
            self.assertEqual("米津玄師", prepared["artist"])
            self.assertEqual(255_000, prepared["lengthMs"])
            self.assertEqual("Lemon 米津玄師", prepared["query"])

    def test_answers_never_kept_are_none(self):
        with tempfile.TemporaryDirectory() as out:
            song = dict(LEMON, id=1, album="Lemon - Single", lengthMs=255_000, editionsMs=[], query="Lemon Kenshi Yonezu")
            with open(os.path.join(out, "songs.json"), "w", encoding="utf-8") as kept:
                json.dump([song], kept)
            with contextlib.redirect_stderr(io.StringIO()):
                replay.prepare(out)
            with open(os.path.join(out, "replay.json"), encoding="utf-8") as kept:
                [prepared] = json.load(kept)
            self.assertIsNone(prepared["songs"])
            self.assertIsNone(prepared["videos"])
            with self.assertRaisesRegex(ValueError, "Missing YouTube Music or Deezer answer"):
                replay.prepare(out, require_answers=True)


if __name__ == "__main__":
    unittest.main()
