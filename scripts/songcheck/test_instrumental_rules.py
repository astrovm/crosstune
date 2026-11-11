import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import instrumental_rules  # noqa: E402


def copy(instrumental, exact=True):
    return {"instrumental": instrumental, "exact": exact, "belongs": True, "seconds": 200.0}


def song(id, problem, title, kind, copies, timed=0):
    return {"id": id, "problem": problem, "title": title, "artist": "Fake Band", "lengthMs": 200_000}, {
        "lyrics": {"kind": kind, "source": "lrclib", "timedLines": timed},
        "lrclib": [{"track": title, "artist": "Fake Band", "copies": copies}],
    }


class InstrumentalRulesTest(unittest.TestCase):
    def test_a_title_says_no_one_sings_as_the_app_reads_it(self):
        for title in ("Song (Instrumental)", "Song - Inst.", "曲 (伴奏)", "曲 off vocal ver.", "Song (Instrumentale)"):
            self.assertTrue(instrumental_rules.says_instrumental(title), title)
        for title in ("Instrumental", "Song (Instrumentalist Mix)", "Song (Karaoke Instrumental)", "Song (Piano Cover) [Instrumental]", "Song (Made Popular By Singer) [Instrumental]"):
            self.assertFalse(instrumental_rules.says_instrumental(title), title)

    def test_each_rule_counts_instrumentals_fixed_and_sung_songs_that_lose_words(self):
        songs = [
            song("i1", "instrumental:strings", "Cello Song", "found", [copy(True), copy(True), copy(False)]),
            song("i2", "instrumental:official", "Hit (Instrumental)", "found", [copy(False)]),
            song("i3", "instrumental:piano", "Quiet", "instrumental", [copy(True)]),
            song("s1", "sung:chart", "Hit", "found", [copy(True), copy(False), copy(False)], timed=30),
            song("s2", "sung:chart", "Other", "found", [copy(False, exact=False)], timed=30),
        ]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / "probe").mkdir()
            (folder / "songs.json").write_text(json.dumps([meta for meta, _ in songs]))
            for meta, report in songs:
                (folder / "probe" / f"{meta['id']}.json").write_text(json.dumps(report))
            # One song not probed yet is left out.
            (folder / "songs.json").write_text(json.dumps([meta for meta, _ in songs] + [{"id": "s3", "problem": "sung:chart", "title": "Later", "artist": "Fake Band"}]))
            instrumental, sung, table = instrumental_rules.score(instrumental_rules.load(folder))
        self.assertEqual((3, 2), (len(instrumental), len(sung)))

        def ids(name):
            fixed, lost = table[name]
            return [p["id"] for p in fixed], [p["id"] for p in lost]

        self.assertEqual((["i1"], []), ids("R2 most copies instrumental"))
        self.assertEqual((["i1"], ["s1"]), ids("R3 any copy instrumental"))
        self.assertEqual((["i2"], []), ids("T title says so"))
        self.assertEqual((["i1", "i2"], []), ids("T or R2 (this change)"))


if __name__ == "__main__":
    unittest.main()
