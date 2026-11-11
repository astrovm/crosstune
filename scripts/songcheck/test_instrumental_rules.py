import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import instrumental_rules  # noqa: E402


def copy(instrumental, exact=True, title=None, artist="Fake Band", seconds=200.0):
    return {"instrumental": instrumental, "exact": exact, "belongs": True, "seconds": seconds, "title": title, "artist": artist}


def song(id, problem, title, kind, copies, timed=0):
    for c in copies:
        c["title"] = c["title"] or title
    return {"id": id, "problem": problem, "title": title, "artist": "Fake Band", "lengthMs": 200_000}, {
        "lyrics": {"kind": kind, "source": "lrclib", "timedLines": timed},
        "lrclib": [{"track": title, "artist": "Fake Band", "copies": copies}],
    }


class InstrumentalRulesTest(unittest.TestCase):
    def test_a_title_says_no_one_sings_as_the_app_reads_it(self):
        # The list SongNamesVersionTest reads too, so both read titles alike.
        titles = Path(__file__).resolve().parents[2] / "app/src/test/resources/instrumental-titles.tsv"
        for line in titles.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            says, title, *artist = line.split("\t")
            self.assertEqual(says == "yes", instrumental_rules.says_instrumental(title, (artist or [""])[0]), line)

    def test_each_rule_counts_instrumentals_fixed_and_sung_songs_that_lose_words(self):
        songs = [
            song("i1", "instrumental:strings", "Cello Song", "found", [copy(True), copy(True), copy(False)]),
            song("i2", "instrumental:official", "Hit (Instrumental)", "found", [copy(False)]),
            song("i3", "instrumental:piano", "Quiet", "instrumental", [copy(True)]),
            song("i4", "instrumental:piano", "Lone Mark", "found", [copy(True)]),
            song("s1", "sung:chart", "Hit", "found", [copy(True), copy(False), copy(False)], timed=30),
            song("s2", "sung:chart", "Other", "found", [copy(False, exact=False)], timed=30),
            # Marked copies of other recordings: tagged, a tribute's, a shorter take's, or saved by the probe as not this one.
            song("s3", "sung:lessknown", "Deep Cut (feat. Guest)", "found", [copy(False, title="Deep Cut"), copy(True, title="Deep Cut (Inst.)"), copy(True, title="Deep Cut (Instrumental)")]),
            song("s4", "sung:lessknown", "B-Side", "found", [copy(False), copy(True, artist="Fake Band Piano Tribute"), copy(True, seconds=95.0)]),
            song("s5", "sung:lessknown", "Saved", "found", [copy(False), {**copy(True), "recording": False}, {**copy(True), "recording": False}]),
        ]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / "probe").mkdir()
            (folder / "songs.json").write_text(json.dumps([meta for meta, _ in songs]))
            for meta, report in songs:
                (folder / "probe" / f"{meta['id']}.json").write_text(json.dumps(report))
            # One song not probed yet is left out.
            (folder / "songs.json").write_text(json.dumps([meta for meta, _ in songs] + [{"id": "s9", "problem": "sung:chart", "title": "Later", "artist": "Fake Band"}]))
            instrumental, sung, table = instrumental_rules.score(instrumental_rules.load(folder))
        self.assertEqual((4, 5), (len(instrumental), len(sung)))

        def ids(name):
            fixed, lost = table[name]
            return [p["id"] for p in fixed], [p["id"] for p in lost]

        # The first version took the words of each sung song whose marked copies are other recordings.
        self.assertEqual((["i1", "i4"], ["s3", "s4", "s5"]), ids("R2 most copies instrumental"))
        self.assertEqual((["i1", "i4"], ["s1", "s3", "s4", "s5"]), ids("R3 any copy instrumental"))
        self.assertEqual((["i2"], []), ids("T title says so"))
        self.assertEqual((["i1", "i4"], []), ids("R2s1 the recording's own copies, 1 or more marked, most"))
        self.assertEqual((["i1", "i2"], []), ids("T or R2s (this change)"))


if __name__ == "__main__":
    unittest.main()
