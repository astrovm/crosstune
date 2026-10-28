import sys
import types
import unittest
from pathlib import Path

# Latin letters go through anyascii as they are, so it needn't be installed to test with them.
sys.modules.setdefault("anyascii", types.SimpleNamespace(anyascii=lambda text: text))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import songcheck  # noqa: E402

START = 20_000


def report(*lines):
    """The app's lines for a song whose preview Shazam puts at 20 s in."""
    return {
        "preview": {"heard": [{"startsAtMs": START}], "lengthMs": 30_000},
        "lyrics": {"lines": [{"text": text, "ms": ms} for text, ms in lines]},
    }


def heard(*words):
    """What Whisper heard, each word at its time in the song."""
    return {"words": [{"word": word, "ms": ms - START} for word, ms in words]}


class LineTimingTest(unittest.TestCase):
    def test_a_line_is_heard_from_where_its_first_word_starts(self):
        # "cien" misheard as "sin": a run started in "conmigo" matches better, at that word's time.
        found = songcheck.line_timing(
            report(("Yo dandote cien", 22_750)),
            heard(("conmigo", 20_591), ("yo", 22_691), ("dandote", 22_900), ("sin", 23_300), ("baby", 23_900)),
        )
        self.assertEqual([22_691], [line["heardMs"] for line in found["lines"]])

    def test_a_line_sung_again_close_by_is_left_out(self):
        # Either time could be the one heard.
        found = songcheck.line_timing(
            report(("Swim swim swim", 25_000), ("Swim swim swim", 31_000), ("Under the water", 35_000)),
            heard(("swim", 25_100), ("swim", 25_400), ("swim", 25_700), ("swim", 31_100), ("swim", 31_400), ("swim", 31_700),
                  ("under", 35_050), ("the", 35_300), ("water", 35_500)),
        )
        self.assertEqual(["Under the water"], [line["text"] for line in found["lines"]])


if __name__ == "__main__":
    unittest.main()
