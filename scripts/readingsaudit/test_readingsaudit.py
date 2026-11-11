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


def word(kanji, kana, senses):
    """A JMdict entry for old_kana: kanji [(text, tags, common)], kana [(text, tags, common, applies to)], senses [(part of speech, misc)]."""
    return {"kanji": [{"text": text, "tags": tags, "common": common} for text, tags, common in kanji],
            "kana": [{"text": text, "tags": tags, "common": common, "appliesToKanji": applies} for text, tags, common, applies in kana],
            "sense": [{"partOfSpeech": pos, "misc": misc} for pos, misc in senses]}


class OldKana(unittest.TestCase):

    def test_names_each_common_word_with_i_or_u_by_its_usual_form(self):
        words = [
            word([("思い出", [], True), ("想い出", [], False)], [("おもいで", [], True, ["*"])], [(["n"], [])]),
            # Usually written in kana, or with no kanji: looked up as it is.
            word([("有難う", [], True)], [("ありがとう", [], True, ["*"])], [(["int"], ["uk"])]),
            word([], [("ちょうど", [], True, [])], [(["adv"], [])]),
            # No common form: the first one there is, but no form for search only.
            word([("迚", ["sK"], False), ("会う", [], False)], [("あう", [], True, ["*"])], [(["v5u"], [])]),
            # Two common words written so: no telling which, so the kana.
            word([("言う", [], True)], [("いう", [], True, ["*"])], [(["v5u"], [])]),
            word([("結う", [], True)], [("いう", [], True, ["*"])], [(["v5u"], [])]),
        ]
        today, _ = readingsaudit.old_kana(words)
        self.assertEqual({"おもいで": "思い出", "ありがとう": "ありがとう", "ちょうど": "ちょうど", "あう": "会う", "いう": "いう"}, today)

    def test_leaves_out_adjectives_rare_words_odd_kana_and_other_letters(self):
        words = [
            # 古い was ふるし, never ふるひ.
            word([("古い", [], True)], [("ふるい", [], True, ["*"])], [(["adj-i"], [])]),
            word([("会う", [], True)], [("あう", [], False, ["*"])], [(["v5u"], [])]),
            word([("言う", [], True)], [("ゆう", ["ik"], True, ["*"])], [(["v5u"], [])]),
            word([], [("ウイスキー", [], True, [])], [(["n"], [])]),
            # い or う first is no old ひ or ふ.
            word([("家", [], True)], [("いえ", [], True, ["*"])], [(["n"], [])]),
        ]
        self.assertEqual(({}, []), readingsaudit.old_kana(words))

    def test_lists_the_words_written_with_hi_or_fu_today_but_archaic_ones(self):
        words = [
            word([("工夫", [], True)], [("くふう", [], True, ["*"])], [(["n"], [])]),
            word([("お昼", [], True)], [("おひる", [], False, ["*"])], [(["n"], []), (["n"], ["arch"])]),
            word([("言ふ", [], False)], [("いふ", [], False, ["*"])], [(["v4h"], ["arch"])]),
            word([], [("ひと", ["ok"], False, [])], [(["n"], [])]),
        ]
        self.assertEqual(["おひる", "くふう"], readingsaudit.old_kana(words)[1])

    def test_writes_the_table_with_where_it_comes_from(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            words = [word([("思い出", [], True)], [("おもいで", [], True, ["*"])], [(["n"], [])]),
                     word([("工夫", [], True)], [("くふう", [], True, ["*"])], [(["n"], [])])]
            (folder / "jmdict.json").write_text(json.dumps({"version": "9.9.9", "dictDate": "2000-01-01", "words": words}, ensure_ascii=False))
            out = folder / "old-kana.txt"
            with mock.patch.object(readingsaudit, "OLD_KANA", out), mock.patch("builtins.print") as said:
                readingsaudit.oldkana(folder)
            lines = out.read_text().splitlines()
            header = "\n".join(line for line in lines if line.startswith("#"))
            self.assertTrue(lines[0].startswith("# JMdict 2000-01-01"))
            # Where it's from, its license and that it's changed, as the Group's licence asks.
            for part in ("jmdict-simplified 9.9.9", "CC BY-SA 4.0", "Changed:", readingsaudit.JMDICT_URL,
                         "https://creativecommons.org/licenses/by-sa/4.0/", "https://www.edrdg.org/edrdg/licence.html"):
                self.assertIn(part, header)
            self.assertEqual(["おもいで\t思い出", "くふう\t工夫", "くふう"], [line for line in lines if not line.startswith("#")])
            self.assertIn("2 words today and 1 modern words", said.call_args.args[0])

    def test_the_committed_table_has_the_header_a_remake_writes(self):
        header = [line for line in readingsaudit.OLD_KANA.read_text().splitlines() if line.startswith("#")]
        date, version = header[0].split()[2].rstrip(","), header[1].split()[3].rstrip(".")
        self.assertEqual(readingsaudit.old_kana_header(date, version), header)


def audit_line(text, built, uni=None, truth=None):
    """One invented line, as the audit writes it. Not a lyric."""
    return {"text": text, "builtIn": built, "uniDic": uni if uni is not None else built, "truth": truth if truth is not None else built}


def write_audit(folder, lines):
    audit = folder / "audit"
    audit.mkdir()
    # A title and an artist are in the song, and must not land in the golden file.
    (audit / "t-01.json").write_text(json.dumps({"id": "t-01", "title": "Not A Real Title", "artist": "Not An Artist", "lines": lines}, ensure_ascii=False))


class Golden(unittest.TestCase):

    def test_locks_each_right_case_once_on_the_shortest_line_and_sorts_by_the_line(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            # 丙 sorts before 甲. The song lists the long line first, so song order is not the file order.
            write_audit(folder, [
                audit_line("甲乙は丙", [["甲乙", "こうおつ"], ["は", None], ["丙", "へい"]]),
                audit_line("甲乙", [["甲乙", "こうおつ"]]),
                # Only the built-in dictionary has this one.
                audit_line("丁戊", [["丁戊", "ていぼ"]], [["丁戊", "ちょうぼ"]], [["丁戊", "ていぼ"]]),
                # Neither dictionary has this one.
                audit_line("己庚", [["己庚", "きこう"]], [["己庚", "きこう"]], [["己庚", "おのこう"]]),
                # The same word twice, read two ways. Each occurrence stays.
                audit_line("丙丙", [["丙", "へい"], ["丙", "てい"]], truth=[["丙", "へい"], ["丙", "てい"]]),
            ])
            dest = folder / "golden.tsv"
            self.assertEqual(0, readingsaudit.golden(folder, dest, []))
            text = dest.read_text(encoding="utf-8")
            self.assertNotIn("Not A Real Title", text)
            self.assertNotIn("Not An Artist", text)
            rows = [line.split("\t") for line in text.splitlines() if line and not line.startswith("#")]
            self.assertEqual([
                ["丁戊", "丁戊", "", "ていぼ", "", "ipadic"],
                ["丙丙", "丙", "0", "へい", "", "both"],
                ["丙丙", "丙", "1", "てい", "", "both"],
                ["甲乙", "甲乙", "", "こうおつ", "", "both"],
            ], rows)
            self.assertIn("readingsaudit.py golden", text)
            self.assertIn("--accept WORD", text)
            first = dest.read_bytes()
            self.assertEqual(0, readingsaudit.golden(folder, dest, []))
            self.assertEqual(first, dest.read_bytes())
            again = folder / "again.tsv"
            readingsaudit.golden(folder, again, [])
            self.assertEqual(first, again.read_bytes())

    def test_a_second_run_keeps_a_row_and_adds_a_new_one(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲乙は丙", [["甲乙", "こうおつ"], ["は", None], ["丙", "へい"]])])
            dest = folder / "golden.tsv"
            readingsaudit.golden(folder, dest, [])
            first = dest.read_bytes()
            # A shorter line appears for 甲乙. The row already there keeps the long line.
            (folder / "audit" / "t-02.json").write_text(json.dumps({"id": "t-02", "lines": [
                audit_line("甲乙", [["甲乙", "こうおつ"]]),
                audit_line("辛壬", [["辛壬", "しんじん"]]),
            ]}, ensure_ascii=False))
            self.assertEqual(0, readingsaudit.golden(folder, dest, []))
            rows = [line.split("\t") for line in dest.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
            self.assertIn(["甲乙は丙", "甲乙", "", "こうおつ", "", "both"], rows)
            self.assertNotIn(["甲乙", "甲乙", "", "こうおつ", "", "both"], rows)
            self.assertIn(["辛壬", "辛壬", "", "しんじん", "", "both"], rows)
            self.assertNotEqual(first, dest.read_bytes())

    def test_a_word_read_otherwise_stays_and_the_command_fails_until_it_is_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲乙", [["甲乙", "こうおつ"]])])
            dest = folder / "golden.tsv"
            readingsaudit.golden(folder, dest, [])
            # The word is now sung, and read, another way. The old row is a regression.
            (folder / "audit" / "t-01.json").write_text(json.dumps({"id": "t-01", "lines": [
                audit_line("甲乙", [["甲乙", "こういつ"]]),
            ]}, ensure_ascii=False))
            with mock.patch("builtins.print") as said:
                code = readingsaudit.golden(folder, dest, [])
            self.assertEqual(1, code)
            said_text = "\n".join(call.args[0] for call in said.call_args_list)
            self.assertIn("regression: 甲乙\tこうおつ\tboth\t甲乙", said_text)
            rows = [line.split("\t") for line in dest.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
            self.assertIn(["甲乙", "甲乙", "", "こうおつ", "", "both"], rows)
            self.assertIn(["甲乙", "甲乙", "", "こういつ", "", "both"], rows)
            with mock.patch("builtins.print"):
                self.assertEqual(0, readingsaudit.golden(folder, dest, ["甲乙"]))
            rows = [line.split("\t") for line in dest.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
            self.assertEqual([["甲乙", "甲乙", "", "こういつ", "", "both"]], rows)

    def test_a_row_whose_line_is_not_in_the_audit_stays(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲乙", [["甲乙", "こうおつ"]])])
            dest = folder / "golden.tsv"
            readingsaudit.golden(folder, dest, [])
            (folder / "audit" / "t-01.json").write_text(json.dumps({"id": "t-01", "lines": [
                audit_line("辛壬", [["辛壬", "しんじん"]]),
            ]}, ensure_ascii=False))
            self.assertEqual(0, readingsaudit.golden(folder, dest, []))
            text = dest.read_text(encoding="utf-8")
            self.assertIn("甲乙\t甲乙\t\tこうおつ\t\tboth", text)
            self.assertIn("辛壬\t辛壬\t\tしんじん\t\tboth", text)


if __name__ == "__main__":
    unittest.main()
