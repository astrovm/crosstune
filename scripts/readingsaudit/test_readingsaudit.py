import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import readingsaudit  # noqa: E402


def entry(kanji, kana):
    """A JMdict entry as jmdict-simplified writes it: kanji [(text, tags)], kana [(text, tags, applies to)]."""
    return {"kanji": [{"text": text, "tags": tags} for text, tags in kanji],
            "kana": [{"text": text, "tags": tags, "appliesToKanji": applies} for text, tags, applies in kana]}


class ListedCandidates(unittest.TestCase):

    def test_keeps_words_of_two_kanji_read_one_way_only(self):
        words = [
            entry([("星空", [])], [("ほしぞら", [], ["*"])]),
            # Two readings, from one entry or from two: no way to tell which is sung.
            entry([("雨空", [])], [("あまぞら", [], ["*"]), ("あまぞら", [], ["*"]), ("うくう", [], ["*"])]),
            entry([("月夜", [])], [("つきよ", [], ["*"])]),
            entry([("月夜", [])], [("げつや", [], ["*"])]),
            # One kanji, or kana first, or a letter that isn't Japanese: not a word the app looks for.
            entry([("夢", [])], [("ゆめ", [], ["*"])]),
            entry([("お茶屋", [])], [("おちゃや", [], ["*"])]),
            entry([("Ｘ線写真", [])], [("エックスせんしゃしん", [], ["*"])]),
        ]
        self.assertEqual({"星空": "ほしぞら"}, readingsaudit.listed_candidates(words))

    def test_reads_katakana_as_hiragana_and_keeps_kana_written_after_the_kanji(self):
        words = [entry([("肉付く", [])], [("ニクヅク", [], ["*"])]), entry([("硝子窓", [])], [("ガラスまど", [], ["*"])])]
        self.assertEqual({"肉付く": "にくづく", "硝子窓": "がらすまど"}, readingsaudit.listed_candidates(words))

    def test_leaves_out_odd_forms_and_odd_kana(self):
        words = [
            # A form for search only is not how lyrics write the word.
            entry([("迚角", ["sK"])], [("とてかく", [], ["*"])]),
            # Rare kana don't count against the usual one; kana for another form don't count at all.
            entry([("上手", []), ("上手い", [])], [("じょうず", [], ["上手"]), ("じょうて", ["rk"], ["*"]), ("うまい", [], ["上手い"])]),
        ]
        self.assertEqual({"上手": "じょうず", "上手い": "うまい"}, readingsaudit.listed_candidates(words))


class Table(unittest.TestCase):

    def test_writes_the_candidates_with_where_they_come_from_and_runs_the_test_that_keeps_them(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            jmdict = {"version": "9.9.9", "dictDate": "2000-01-01", "words": [entry([("星空", [])], [("ほしぞら", [], ["*"])])]}
            (folder / "jmdict.json").write_text(json.dumps(jmdict, ensure_ascii=False))
            with mock.patch.object(readingsaudit, "run") as run, mock.patch("builtins.print"):
                readingsaudit.table(folder)
            run.assert_called_once_with(folder, "com.astrovm.crosstune.songcheck.JmdictReadings")
            lines = (folder / "listed.txt").read_text().splitlines()
            self.assertEqual("星空\tほしぞら", lines[-1])
            header = "\n".join(line for line in lines if line.startswith("#"))
            self.assertTrue(lines[0].startswith("# JMdict 2000-01-01"))
            self.assertIn("jmdict-simplified 9.9.9", header)
            # Where the words come from, their licence and that they were changed, as CC BY-SA 4.0 asks.
            for said in ("Electronic Dictionary Research and Development Group", readingsaudit.JMDICT_URL, "CC BY-SA 4.0",
                         readingsaudit.CC_BY_SA_URL, readingsaudit.EDRDG_LICENCE_URL, "Changed:"):
                self.assertIn(said, header)

    def test_the_committed_table_has_the_header_a_remake_writes(self):
        table = (Path(__file__).resolve().parents[2] / "app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt").read_text()
        header = [line for line in table.splitlines() if line.startswith("#")]
        date, version = header[0].split()[2].rstrip(","), header[1].split()[3].rstrip(".")
        self.assertEqual(readingsaudit.table_header(date, version), header)


if __name__ == "__main__":
    unittest.main()
