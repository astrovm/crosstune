import os
import sys
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


if __name__ == "__main__":
    unittest.main()
