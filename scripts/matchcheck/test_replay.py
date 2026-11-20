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


if __name__ == "__main__":
    unittest.main()
