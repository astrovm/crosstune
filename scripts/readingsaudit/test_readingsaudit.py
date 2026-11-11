import datetime
import hashlib
import http.client
import io
import json
import os
import re
import sys
import tempfile
import unittest
import urllib.error
import zipfile
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
            with mock.patch.object(readingsaudit, "run") as run, mock.patch("builtins.print"), \
                    mock.patch.object(readingsaudit, "TABLE", folder / "none.txt"):
                readingsaudit.table(folder, "./gradlew")
            run.assert_called_once_with(folder, "com.astrovm.crosstune.songcheck.JmdictReadings", "./gradlew")
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
            # No file yet, so no date to keep to.
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


def lexicon_reader(words, after=None):
    """A made-up app: longest known word first, else one letter unread. [after] maps (letter before, letter) to its reading there."""
    after = after or {}

    def read(stretch, dictionary):
        known = words[dictionary] if dictionary in words else words
        parts, i = [], 0
        while i < len(stretch):
            n = next((n for n in range(len(stretch) - i, 0, -1) if stretch[i:i + n] in known), 0)
            if n:
                piece = stretch[i:i + n]
                before = stretch[i - 1] if i else ""
                parts.append([piece, after.get((before, piece), known[piece])])
                i += n
            else:
                parts.append([stretch[i], None])
                i += 1
        return parts
    return read


class FakeContexts:
    """GoldenContexts as a made-up app answers it: the same search as GoldenReadings, in Python, on [read]."""

    def __init__(self, read):
        self.read = read
        self.jobs = []

    def __call__(self, folder, job):
        self.jobs.append(job)
        rows = [[self.ruby_at(r[0], r[1], r[2], d) == r[3] if r[4] in (d, "both") else None for d in ("ipadic", "unidic")] for r in job["rows"]]
        return {"rows": rows, "cases": [self.shortest(*case) for case in job["cases"]]}

    def ruby_at(self, stretch, word, index, dictionary):
        parts = self.read(stretch, dictionary)
        starts = readingsaudit.find_starts(stretch, word)
        if index is None and len(starts) == 1:
            start = starts[0]
        elif index is not None and len(starts) > 1 and index < len(starts):
            start = starts[index]
        else:
            return None
        bounds, at = {0}, 0
        for text, _reading in parts:
            at += len(text)
            bounds.add(at)
        if start not in bounds or start + len(word) not in bounds:
            return None
        return readingsaudit.ruby_over(parts, start, start + len(word))

    def shortest(self, line, word, occurrence, ruby, dictionary):
        start = readingsaudit.find_starts(line, word)[occurrence]
        for length in range(len(word), len(line) + 1):
            for begin in range(max(0, start + len(word) - length), start + 1):
                end = begin + length
                if end > len(line):
                    break
                stretch = line[begin:end]
                starts = readingsaudit.find_starts(stretch, word)
                index = starts.index(start - begin) if len(starts) > 1 else None
                if self.ruby_at(stretch, word, index, dictionary) == ruby:
                    return [stretch, index]
        return None


# The made-up app's words. 日 is び after 曜, and the second 丙 of two is てい. UniDic reads 丁戊 otherwise.
WORDS = {"月": "げつ", "曜": "よう", "日": "ひ", "丙": "へい", "甲乙": "こうおつ", "丁戊": "ていぼ", "己庚": "きこう"}
APP = lexicon_reader({"ipadic": WORDS, "unidic": {**WORDS, "丁戊": "ちょうぼ"}}, {("曜", "日"): "び", ("丙", "丙"): "てい"})


def golden_rows(dest):
    return [line.split("\t") for line in dest.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]


class Golden(unittest.TestCase):

    def run_golden(self, folder, dest, accept=(), app=APP):
        fake = FakeContexts(app)
        with mock.patch("builtins.print") as said:
            code = readingsaudit.golden(folder, dest, accept, fake)
        return code, "\n".join(call.args[0] for call in said.call_args_list), fake

    def test_locks_every_right_reading_on_the_shortest_stretch_that_reads_so(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [
                audit_line("月曜日に", [["月", "げつ"], ["曜", "よう"], ["日", "び"], ["に", None]]),
                audit_line("その日に", [["その", None], ["日", "ひ"], ["に", None]]),
                # Only the built-in dictionary reads this one as it is sung.
                audit_line("丁戊", [["丁戊", "ていぼ"]], [["丁戊", "ちょうぼ"]], [["丁戊", "ていぼ"]]),
                # Neither dictionary does.
                audit_line("己庚", [["己庚", "きこう"]], [["己庚", "きこう"]], [["己庚", "おのこう"]]),
                # The same word twice, read two ways. Each occurrence is its own row.
                audit_line("丙丙", [["丙", "へい"], ["丙", "てい"]]),
            ])
            dest = folder / "golden.tsv"
            code, said, fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            text = dest.read_text(encoding="utf-8")
            self.assertNotIn("Not A Real Title", text)
            self.assertNotIn("Not An Artist", text)
            self.assertEqual([
                ["丁戊", "丁戊", "", "ていぼ", "丁戊=ていぼ", "ipadic"],
                ["丙", "丙", "", "へい", "丙=へい", "both"],
                ["丙丙", "丙", "1", "てい", "丙=てい", "both"],
                ["日", "日", "", "ひ", "日=ひ", "both"],
                ["曜", "曜", "", "よう", "曜=よう", "both"],
                ["曜日", "日", "", "び", "日=び", "both"],
                ["月", "月", "", "げつ", "月=げつ", "both"],
            ], golden_rows(dest))
            lines = text.splitlines()
            self.assertEqual(readingsaudit.GOLDEN_HEADER + ["# rows: 7"], lines[:len(readingsaudit.GOLDEN_HEADER) + 1])
            # 丁戊 with IPADIC, and the rest with both.
            self.assertIn("golden: 7 rows, 7 new, 0 regressions, 13 of 13 right readings locked (100.0%)", said)
            self.assertEqual(13, len(fake.jobs[0]["cases"]))
            first = dest.read_bytes()
            code, said, fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertEqual(first, dest.read_bytes())
            self.assertIn("golden: 7 rows, 0 new", said)
            # Each row is checked again, the stretch with its index as a number.
            self.assertIn(["丙丙", "丙", 1, "丙=てい", "both"], fake.jobs[0]["rows"])

    def test_a_row_already_there_keeps_locking_its_reading(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("月曜日に", [["月", "げつ"], ["曜", "よう"], ["日", "び"], ["に", None]])])
            dest = folder / "golden.tsv"
            # Locked before on a longer stretch. It still holds, so no second row joins it.
            readingsaudit.write_golden(dest, [("月曜日", "日", "", "び", "日=び", "both")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            rows = golden_rows(dest)
            self.assertIn(["月曜日", "日", "", "び", "日=び", "both"], rows)
            self.assertNotIn(["曜日", "日", "", "び", "日=び", "both"], rows)
            self.assertIn("golden: 3 rows, 2 new", said)

    def test_a_row_that_locks_one_dictionary_joins_the_other_when_both_read_so(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲乙", [["甲乙", "こうおつ"]])])
            dest = folder / "golden.tsv"
            readingsaudit.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "unidic")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertEqual([["甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both"]], golden_rows(dest))

    def test_a_reading_that_changes_stays_locked_and_fails_until_it_is_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲乙", [["甲乙", "こうおつ"]])])
            dest = folder / "golden.tsv"
            self.run_golden(folder, dest)
            # The app now reads it another way, and the song is sung that way.
            (folder / "audit" / "t-01.json").write_text(json.dumps({"id": "t-01", "lines": [
                audit_line("甲乙", [["甲乙", "こういつ"]]),
            ]}, ensure_ascii=False))
            app = lexicon_reader({**WORDS, "甲乙": "こういつ"})
            code, said, _fake = self.run_golden(folder, dest, app=app)
            self.assertEqual(1, code)
            self.assertIn("regression: 甲乙\tこうおつ\tboth\t甲乙\t甲乙=こうおつ", said)
            rows = golden_rows(dest)
            self.assertIn(["甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both"], rows)
            self.assertIn(["甲乙", "甲乙", "", "こういつ", "甲乙=こういつ", "both"], rows)
            # Accepting another kana of the word drops nothing.
            self.assertEqual(1, self.run_golden(folder, dest, [("甲乙", "こういつ")], app)[0])
            code, said, _fake = self.run_golden(folder, dest, [("甲乙", "こうおつ")], app)
            self.assertEqual(0, code, said)
            self.assertEqual([["甲乙", "甲乙", "", "こういつ", "甲乙=こういつ", "both"]], golden_rows(dest))

    def test_a_reading_the_app_does_not_give_on_its_line_is_named_and_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            # An audit from before a change: it says ちょうぼ with IPADIC too, the app now says ていぼ.
            write_audit(folder, [audit_line("丁戊", [["丁戊", "ちょうぼ"]])])
            dest = folder / "golden.tsv"
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(1, code)
            self.assertIn("not locked: 丁戊\tちょうぼ\tipadic", said)
            self.assertIn("1 of 2 right readings locked (50.0%)", said)

    def test_a_word_a_row_can_not_hold_is_named_and_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("甲|乙", [["甲|乙", "こうおつ"]])])
            dest = folder / "golden.tsv"
            code, said, fake = self.run_golden(folder, dest)
            self.assertEqual(1, code)
            self.assertEqual([], fake.jobs[0]["cases"])
            self.assertIn("not locked: 甲|乙\tこうおつ\tipadic", said)
            self.assertIn("0 of 2 right readings locked (0.0%)", said)

    def test_a_row_whose_stretch_is_not_in_the_audit_stays(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("その日に", [["その", None], ["日", "ひ"], ["に", None]])])
            dest = folder / "golden.tsv"
            readingsaudit.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertEqual([["日", "日", "", "ひ", "日=ひ", "both"], ["甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both"]], golden_rows(dest))

    def test_an_empty_audit_writes_no_share(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            # A line with no ruby, and a chorus sung again, add nothing.
            no_ruby = {"text": "甲乙", "builtIn": [["甲乙", "こうおつ"]], "uniDic": [["甲乙", "こうおつ"]], "truth": []}
            write_audit(folder, [audit_line("その", [["その", None]]), no_ruby, audit_line("その", [["その", None]])])
            dest = folder / "golden.tsv"
            readingsaudit.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertIn("0 of 0 right readings locked (none)", said)

    def test_no_audit_stops(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(SystemExit):
                self.run_golden(Path(folder), Path(folder) / "golden.tsv")

    def test_a_row_is_six_whole_fields_that_read_back_as_written(self):
        good = ("思い出", "思い出", "", "おもいで", "思=おも|い|出=で", "both")
        readingsaudit.check_fields(good)
        for bad in [
            ("#思い出", "思い出", "", "おもいで", "思=おも|い|出=で", "both"),
            ("", "思い出", "", "おもいで", "思=おも|い|出=で", "both"),
            ("思い出 ", "思い出", "", "おもいで", "思=おも|い|出=で", "both"),
            ("思い出", "思い出", "", "おもいで", "思=おも|い|出=で", "sudachi"),
            ("思い出", "思い出", "x", "おもいで", "思=おも|い|出=で", "both"),
            ("思い出", "思い出", "", "おもいで", "思=おも|い", "both"),
            ("思い出", "思い出", "", "おもいて", "思=おも|い|出=で", "both"),
            ("思い", "思い出", "", "おもいで", "思=おも|い|出=で", "both"),
        ]:
            with self.assertRaises(SystemExit, msg=bad):
                readingsaudit.check_fields(bad)
        self.assertTrue(readingsaudit.writable("思い出", "思=おも|い|出=で"))
        self.assertFalse(readingsaudit.writable("思=出", "思=おも|=|出=で"))
        self.assertFalse(readingsaudit.writable("思い出", "思=おも\n|い|出=で"))

    def test_a_broken_row_in_the_file_stops_the_command(self):
        with tempfile.TemporaryDirectory() as folder:
            dest = Path(folder) / "golden.tsv"
            self.assertEqual([], readingsaudit.read_golden(dest))
            dest.write_text("# rows: 1\r\n甲乙\t甲乙\t\tこうおつ\t甲乙=こうおつ\tboth\r\n\n", encoding="utf-8")
            self.assertEqual([("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")], readingsaudit.read_golden(dest))
            dest.write_text("甲乙\t甲乙\t\tこうおつ\tboth\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                readingsaudit.read_golden(dest)

    def test_a_row_names_an_occurrence_its_stretch_has(self):
        self.assertTrue(readingsaudit.covers(("曜日", "日", "", "び", "日=び", "both"), "月曜日に", "日", 2))
        self.assertFalse(readingsaudit.covers(("曜日", "日", "", "び", "日=び", "both"), "日曜日", "日", 0))
        self.assertFalse(readingsaudit.covers(("日日", "日", "2", "ひ", "日=ひ", "both"), "日日", "日", 0))

    def test_the_contexts_come_from_the_app_through_the_build_box(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            answer = {"rows": [], "cases": [["日", None]]}

            def songcheck(where, test):
                self.assertEqual("com.astrovm.crosstune.songcheck.GoldenContexts", test)
                self.assertEqual({"rows": [], "cases": [["日", "日", 0, "日=ひ", "ipadic"]]}, json.loads((where / "golden-job.json").read_text()))
                (where / "golden-answer.json").write_text(json.dumps(answer))
                return 0
            (folder / "golden-answer.json").write_text("{}")
            with mock.patch.object(readingsaudit, "songcheck", side_effect=songcheck):
                self.assertEqual(answer, readingsaudit.golden_contexts(folder, {"rows": [], "cases": [["日", "日", 0, "日=ひ", "ipadic"]]}))
            # A failed run, or one that wrote nothing, stops the command: an old answer is never read.
            with mock.patch.object(readingsaudit, "songcheck", return_value=1), self.assertRaises(SystemExit):
                readingsaudit.golden_contexts(folder, {"rows": [], "cases": []})
            with mock.patch.object(readingsaudit, "songcheck", return_value=0), self.assertRaises(SystemExit):
                readingsaudit.golden_contexts(folder, {"rows": [], "cases": []})

    def test_the_songcheck_runs_in_the_build_box_on_the_audit_folder(self):
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=3) as call:
            self.assertEqual(3, readingsaudit.songcheck(Path("/audit"), "com.example.Test"))
        command = call.call_args.args[0]
        self.assertIn(":app:songCheck", command)
        self.assertIn("-PsongcheckDir=/audit", command)
        self.assertIn("-PsongcheckTest=com.example.Test", command)
        self.assertEqual("/audit", call.call_args.kwargs["env"]["SONGCHECK_DIR"])

    def test_run_exits_with_the_songchecks_code(self):
        with mock.patch.object(readingsaudit, "songcheck", return_value=2), self.assertRaises(SystemExit) as stop:
            readingsaudit.run(Path("/audit"))
        self.assertEqual(2, stop.exception.code)

    def test_the_command_takes_a_word_and_its_kana_to_accept(self):
        argv = ["readingsaudit.py", "golden", "--accept", "甲乙", "こうおつ", "--out", "/x.tsv"]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(readingsaudit, "golden", return_value=0) as golden, \
                self.assertRaises(SystemExit) as stop:
            readingsaudit.main()
        self.assertEqual(0, stop.exception.code)
        self.assertEqual([["甲乙", "こうおつ"]], golden.call_args.args[2])
        self.assertEqual(Path("/x.tsv"), golden.call_args.args[1])

    def test_the_committed_file_has_the_header_a_remake_writes_and_its_count(self):
        text = readingsaudit.GOLDEN_FILE.read_text(encoding="utf-8")
        rows = readingsaudit.read_golden(readingsaudit.GOLDEN_FILE)
        header = [line for line in text.splitlines() if line.startswith("#")]
        self.assertEqual(readingsaudit.GOLDEN_HEADER + [f"# rows: {len(rows)}"], header)
        self.assertGreater(len(rows), 0)
        self.assertEqual(sorted(rows, key=readingsaudit.row_sort_key), rows)


TAG = "9.9.9+20000101000000"
ZIP_NAME = f"jmdict-eng-{TAG}.json.zip"
DOWNLOAD = f"https://github.com/scriptin/jmdict-simplified/releases/download/9.9.9%2B20000101000000/{ZIP_NAME}"


def zipped(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        for name, text in files.items():
            archive.writestr(name, text)
    return out.getvalue()


class Answer(io.BytesIO):
    """An HTTP answer that remembers how much was asked of it at most.

    An incomplete one fails to read as http.client does when the connection ends before the length it gave.
    """

    asked = None

    def __init__(self, body, incomplete=False):
        super().__init__(body)
        self.incomplete = incomplete

    def read(self, size=-1):
        self.asked = size
        if self.incomplete:
            raise http.client.IncompleteRead(b"", 10)
        return super().read(size)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class FakeGitHub:
    """Answers the release API and the download the way GitHub does, from what each test gives it."""

    def __init__(self, data, assets=None, tag=TAG):
        self.data = data
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        self.release = {"tag_name": tag, "assets": assets if assets is not None else [
            {"name": f"jmdict-eng-common-{tag}.json.zip", "size": 1, "digest": "sha256:00",
             "browser_download_url": DOWNLOAD.replace("jmdict-eng-", "jmdict-eng-common-")},
            {"name": ZIP_NAME, "size": len(data), "digest": digest, "browser_download_url": DOWNLOAD},
        ]}
        self.asked = []
        self.timeouts = []
        # Errors to raise, one per call, before answering: the network failing a few times.
        self.failures = []
        # Answers to cut, one per call after the failures: "short" ends early with no error, as a
        # connection closed before the end does; "split" ends the same way, inside a character of
        # more than one byte; "incomplete" fails to read, as http.client says.
        self.cuts = []
        self.answers = []

    def __call__(self, request, timeout=None):
        self.asked.append(request)
        self.timeouts.append(timeout)
        if self.failures:
            raise self.failures.pop(0)
        body = json.dumps(self.release, ensure_ascii=False).encode() if request.full_url.startswith(readingsaudit.JMDICT_RELEASES) else self.data
        cut = self.cuts.pop(0) if self.cuts else None
        if cut == "short":
            body = body[:len(body) // 2]
        elif cut == "split":
            body = body[:body.index("語".encode()) + 1]
        answer = Answer(body, incomplete=cut == "incomplete")
        self.answers.append(answer)
        return answer


class Fetch(unittest.TestCase):

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory())) / "data"
        self.enterContext(mock.patch("builtins.print"))
        self.enterContext(mock.patch.dict(os.environ, {"GH_TOKEN": "", "GITHUB_OUTPUT": ""}))
        self.waits = []
        self.enterContext(mock.patch.object(readingsaudit.time, "sleep", self.waits.append))

    def test_asks_with_a_timeout_and_tries_three_times_with_a_longer_wait_each_time(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        github.failures = [urllib.error.URLError("no route"), TimeoutError("timed out")]
        readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(4, len(github.asked))
        self.assertEqual([readingsaudit.TIMEOUT] * 4, github.timeouts)
        self.assertEqual([2, 4], self.waits)
        self.assertTrue((self.folder / "jmdict.json").exists())

    def test_gives_up_after_three_tries_and_says_why(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        github.failures = [urllib.error.URLError("no route")] * 3
        with self.assertRaisesRegex(SystemExit, "3 tries.*no route"):
            readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual([2, 4], self.waits)

    def test_tries_again_on_a_server_error_but_not_on_a_missing_release(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        github.failures = [urllib.error.HTTPError("u", 502, "Bad Gateway", None, None)]
        readingsaudit.fetch(self.folder, opener=github)
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        github.failures = [urllib.error.HTTPError("u", 404, "Not Found", None, None)]
        with self.assertRaisesRegex(SystemExit, "404"):
            readingsaudit.fetch(self.folder, "1.2.3+20000101000000", opener=github)
        self.assertEqual(1, len(github.asked))

    def test_tries_again_when_github_asks_to_wait(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        busy = urllib.error.HTTPError("u", 429, "Too Many Requests", None, io.BytesIO(b"wait"))
        github.failures = [busy]
        readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(3, len(github.asked))
        self.assertEqual([2], self.waits)
        # The connection of the answer it didn't use is closed.
        self.assertTrue(busy.fp.closed)

    def test_tries_again_when_an_answer_is_cut_off(self):
        data = zipped({"jmdict-eng-9.9.9.json": '{"dictDate": "2000-01-01"}'})
        # The release cut short, then its body that http.client finds incomplete, then the zip cut short.
        github = FakeGitHub(data)
        github.cuts = ["short", "incomplete", None, "short"]
        readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(5, len(github.asked))
        self.assertEqual([2, 4, 2], self.waits)
        self.assertEqual('{"dictDate": "2000-01-01"}', (self.folder / "jmdict.json").read_text())

    def test_tries_again_when_the_release_is_cut_inside_a_character(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        github = FakeGitHub(data)
        github.release["body"] = "語"
        github.cuts = ["split"]
        readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(3, len(github.asked))
        self.assertEqual([2], self.waits)

    def test_says_the_download_was_cut_off_or_the_release_gives_a_wrong_size(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        github = FakeGitHub(data)
        github.release["assets"][1]["size"] = len(data) + 1
        with self.assertRaises(SystemExit) as stop:
            readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(
            f"{DOWNLOAD}: failed 3 tries. The last: got {len(data)} bytes, and release {TAG} says"
            f" {ZIP_NAME} is {len(data) + 1}. Either the download was cut off, or the release gives a wrong size.",
            stop.exception.code)
        self.assertFalse((self.folder / "jmdict.json").exists())

    def test_gives_up_on_answers_cut_off_three_times_and_says_why(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        for cuts, said in [
            (["short"] * 3, r"releases/latest: failed 3 tries\. The last: its JSON is cut off or broken"),
            (["incomplete"] * 3, r"releases/latest: failed 3 tries\. The last: IncompleteRead\(0 bytes read, 10 more expected\)"),
            ([None] + ["short"] * 3, rf"{re.escape(ZIP_NAME)}: failed 3 tries\. The last: got {len(data) // 2} bytes, and release {re.escape(TAG)} says {re.escape(ZIP_NAME)} is {len(data)}\."),
        ]:
            github = FakeGitHub(data)
            github.cuts = cuts
            with self.subTest(said), self.assertRaisesRegex(SystemExit, said) as stop:
                readingsaudit.fetch(self.folder, opener=github)
            self.assertIsInstance(stop.exception.code, str)
            self.assertFalse((self.folder / "jmdict.json").exists())

    def test_reads_no_more_of_the_download_than_one_byte_past_its_size(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        github = FakeGitHub(data + b"x" * 1000)
        github.release["assets"][1]["size"] = len(data)
        with self.assertRaisesRegex(SystemExit, f"is {len(data) + 1} bytes or more"):
            readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(len(data) + 1, github.answers[1].asked)

    def test_takes_no_zip_bigger_than_the_cap(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        github.release["assets"][1]["size"] = readingsaudit.MAX_ZIP_BYTES + 1
        with self.assertRaisesRegex(SystemExit, "bigger than"):
            readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(1, len(github.asked))

    def test_unzips_no_more_than_the_cap_whatever_the_zip_says(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "x" * 100}))
        with mock.patch.object(readingsaudit, "MAX_JSON_BYTES", 99), self.assertRaisesRegex(SystemExit, "more than 99 bytes"):
            readingsaudit.fetch(self.folder, opener=github)
        self.assertFalse((self.folder / "jmdict.json").exists())
        # A zip that says its file is small but unzips to more is stopped, and nothing is kept.
        data = bytearray(zipped({"jmdict-eng-9.9.9.json": "x" * 100}))
        with zipfile.ZipFile(io.BytesIO(bytes(data))) as archive:
            info = archive.infolist()[0]
        # The size in the central directory, 4 bytes at offset 24 of its entry.
        central = data.rindex(b"PK\x01\x02")
        data[central + 24:central + 28] = (10).to_bytes(4, "little")
        github = FakeGitHub(bytes(data))
        with mock.patch.object(readingsaudit, "MAX_JSON_BYTES", 99), self.assertRaisesRegex(SystemExit, "doesn't unzip: Bad CRC"):
            readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(100, info.file_size)
        self.assertFalse((self.folder / "jmdict.json").exists())
        self.assertEqual([], list(self.folder.iterdir()))

    def test_keeps_the_jmdict_json_it_had_when_unzipping_fails(self):
        self.folder.mkdir()
        (self.folder / "jmdict.json").write_text('{"dictDate": "1999-01-01"}')
        data = bytearray(zipped({"jmdict-eng-9.9.9.json": "x" * 100}))
        central = data.rindex(b"PK\x01\x02")
        data[central + 24:central + 28] = (10).to_bytes(4, "little")
        with self.assertRaisesRegex(SystemExit, "doesn't unzip: Bad CRC"):
            readingsaudit.fetch(self.folder, opener=FakeGitHub(bytes(data)))
        self.assertEqual('{"dictDate": "1999-01-01"}', (self.folder / "jmdict.json").read_text())
        self.assertEqual(["jmdict.json"], [p.name for p in self.folder.iterdir()])

    def test_takes_only_a_tag_jmdict_simplified_names_its_releases_with(self):
        for tag in ("9.9.9+20000101000000,x", "9.9.9+20000101000000`x`", "latest", "9.9.9+2000"):
            with self.subTest(tag):
                with self.assertRaisesRegex(SystemExit, "isn't a jmdict-simplified release tag"):
                    readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped({"a.json": "{}"}), tag=tag))
                github = FakeGitHub(zipped({"a.json": "{}"}))
                with self.assertRaisesRegex(SystemExit, "isn't a jmdict-simplified release tag"):
                    readingsaudit.fetch(self.folder, tag, opener=github)
                self.assertEqual([], github.asked)
        self.assertFalse((self.folder / "jmdict.json").exists())

    def test_names_the_tag_for_the_workflow(self):
        output = self.folder.parent / "output"
        output.write_text("before=1\n")
        with mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}):
            readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped({"a.json": "{}"})))
        self.assertEqual(f"before=1\ntag={TAG}\n", output.read_text())

    def test_puts_the_newest_jmdict_eng_in_the_folder_when_it_is_what_the_release_says(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": '{"dictDate": "2000-01-01"}'}))
        self.assertEqual(TAG, readingsaudit.fetch(self.folder, opener=github))
        self.assertEqual('{"dictDate": "2000-01-01"}', (self.folder / "jmdict.json").read_text())
        self.assertEqual([f"{readingsaudit.JMDICT_RELEASES}/latest", DOWNLOAD], [r.full_url for r in github.asked])
        self.assertIsNone(github.asked[0].get_header("Authorization"))

    def test_asks_for_the_release_of_a_tag_with_the_runners_token(self):
        github = FakeGitHub(zipped({"jmdict-eng-9.9.9.json": "{}"}))
        with mock.patch.dict(os.environ, {"GH_TOKEN": "fake-token"}):
            readingsaudit.fetch(self.folder, TAG, opener=github)
        self.assertEqual(f"{readingsaudit.JMDICT_RELEASES}/tags/9.9.9%2B20000101000000", github.asked[0].full_url)
        self.assertEqual("Bearer fake-token", github.asked[0].get_header("Authorization"))
        # The token is GitHub's API's, never sent with the download.
        self.assertIsNone(github.asked[1].get_header("Authorization"))

    def test_keeps_nothing_that_is_not_what_the_release_says(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        asset = {"name": ZIP_NAME, "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                 "browser_download_url": DOWNLOAD}
        for assets, said in [
            ([], "has 0 jmdict-eng zips"),
            ([asset, {**asset, "name": f"jmdict-eng-{TAG}-again.json.zip"}], "has 2 jmdict-eng zips"),
            ([{**asset, "digest": None}], "gives no SHA-256"),
            ([{k: v for k, v in asset.items() if k != "digest"}], "gives no SHA-256"),
            ([{**asset, "browser_download_url": DOWNLOAD.replace("20000101000000", "19990101000000")}], "isn't under release"),
            ([{**asset, "size": len(data) + 1}], f"got {len(data)} bytes, and release {re.escape(TAG)} says {re.escape(ZIP_NAME)} is {len(data) + 1}"),
            ([{**asset, "digest": "sha256:" + "0" * 64}], "has sha256:"),
        ]:
            with self.subTest(said), self.assertRaisesRegex(SystemExit, said):
                readingsaudit.fetch(self.folder, opener=FakeGitHub(data, assets))
            self.assertFalse((self.folder / "jmdict.json").exists())

    def test_takes_the_download_with_the_tags_plus_as_it_is_or_escaped(self):
        data = zipped({"jmdict-eng-9.9.9.json": "{}"})
        github = FakeGitHub(data)
        github.release["assets"][1]["browser_download_url"] = DOWNLOAD.replace("%2B", "+")
        readingsaudit.fetch(self.folder, opener=github)
        self.assertEqual(DOWNLOAD.replace("%2B", "+"), github.asked[1].full_url)

    def test_wants_one_json_file_in_the_zip(self):
        for files, count in [({"a.json": "{}", "b.json": "{}"}, 2), ({"README.md": ""}, 0)]:
            with self.subTest(count), self.assertRaisesRegex(SystemExit, f"holds {count} JSON files"):
                readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped(files)))

    def test_writes_jmdict_json_whatever_path_the_zip_gives(self):
        readingsaudit.fetch(self.folder, opener=FakeGitHub(zipped({"../../escape.json": "{}"})))
        self.assertEqual(["jmdict.json"], [p.name for p in self.folder.parent.rglob("*.json")])


def dated(folder, name, date):
    path = folder / name
    path.write_text("\n".join(readingsaudit.table_header(date, "9.9.9") + ["星空\tほしぞら"]) + "\n")
    return path


class Age(unittest.TestCase):

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.said = self.enterContext(mock.patch("builtins.print"))

    def test_passes_up_to_the_age_and_fails_past_it_naming_the_old_file(self):
        fresh = dated(self.folder, "fresh.txt", "2000-03-01")
        old = dated(self.folder, "old.txt", "2000-01-01")
        self.assertEqual(0, readingsaudit.age([fresh, old], datetime.date(2000, 3, 31), max_days=90))
        self.assertEqual(1, readingsaudit.age([fresh, old], datetime.date(2000, 4, 1), max_days=90))
        said = [call.args[0] for call in self.said.call_args_list]
        self.assertIn("old.txt: JMdict 2000-01-01, 91 days old", said)
        self.assertTrue(any(line.startswith("old.txt is older than 90 days") for line in said))
        self.assertFalse(any(line.startswith("fresh.txt is older") for line in said))

    def test_a_table_with_a_date_that_is_not_one_stops_the_check_and_says_so(self):
        path = dated(self.folder, "bad.txt", "2026-13-45")
        with self.assertRaisesRegex(SystemExit, "bad.txt has a JMdict date that is no date: 2026-13-45"):
            readingsaudit.age([path], datetime.date(2000, 1, 1))

    def test_a_table_with_no_date_stops_the_check(self):
        path = self.folder / "undated.txt"
        path.write_text("星空\tほしぞら\n")
        with self.assertRaisesRegex(SystemExit, "no JMdict date"):
            readingsaudit.age([path], datetime.date(2000, 1, 1))

    def test_the_committed_tables_have_a_date_the_check_reads(self):
        for path in (readingsaudit.TABLE, readingsaudit.OLD_KANA):
            self.assertIsInstance(readingsaudit.header_date(path), datetime.date)


class NoDowngrade(unittest.TestCase):
    """A remake never goes back to an older JMdict than the committed lists: the licence asks for the newest."""

    def setUp(self):
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(mock.patch("builtins.print"))
        self.committed = dated(self.folder, "committed.txt", "2000-02-01")
        jmdict = {"version": "9.9.9", "dictDate": "2000-01-01", "words": [entry([("星空", [])], [("ほしぞら", [], ["*"])])]}
        (self.folder / "jmdict.json").write_text(json.dumps(jmdict, ensure_ascii=False))

    def test_table_stops_on_an_older_jmdict_and_says_why(self):
        with mock.patch.object(readingsaudit, "TABLE", self.committed), mock.patch.object(readingsaudit, "run") as run, \
                self.assertRaisesRegex(SystemExit, "JMdict 2000-01-01 is older than the 2000-02-01 of committed.txt"):
            readingsaudit.table(self.folder)
        run.assert_not_called()
        self.assertFalse((self.folder / "listed.txt").exists())

    def test_oldkana_stops_on_an_older_jmdict_and_writes_nothing(self):
        with mock.patch.object(readingsaudit, "OLD_KANA", self.committed), \
                self.assertRaisesRegex(SystemExit, "newest version"):
            readingsaudit.oldkana(self.folder)
        self.assertIn("2000-02-01", self.committed.read_text())

    def test_the_same_or_a_newer_jmdict_goes_on(self):
        dated(self.folder, "committed.txt", "2000-01-01")
        with mock.patch.object(readingsaudit, "OLD_KANA", self.committed):
            readingsaudit.oldkana(self.folder)
        self.assertTrue(self.committed.read_text().startswith("# JMdict 2000-01-01, "))


class Run(unittest.TestCase):

    def gradle_command(self, *args, **kwargs):
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=3) as call, self.assertRaises(SystemExit) as stop:
            readingsaudit.run(Path("/fake/folder"), "Some.Test", *args, **kwargs)
        self.assertEqual(3, stop.exception.code)
        self.assertEqual("/fake/folder", call.call_args.kwargs["env"]["SONGCHECK_DIR"])
        return call.call_args.args[0]

    def test_runs_gradle_in_the_box_unless_told_another_way(self):
        tail = [":app:songCheck", "-PsongcheckDir=/fake/folder", "-PsongcheckTest=Some.Test"]
        box = str(readingsaudit.ROOT / "scripts/gradle-box/gradle-box.sh")
        self.assertEqual(["nice", "-n", "10", box, "--max-workers=2", *tail], self.gradle_command())
        self.assertEqual(["nice", "-n", "10", "./gradlew", "--build-cache", *tail], self.gradle_command("./gradlew --build-cache"))


class Main(unittest.TestCase):

    def test_passes_each_step_its_options(self):
        with mock.patch.object(readingsaudit, "data_dir", return_value=Path("/fake")):
            with mock.patch.object(readingsaudit, "table") as table:
                readingsaudit.main(["table", "--gradle", "./gradlew"])
            table.assert_called_once_with(Path("/fake"), "./gradlew")
            with mock.patch.object(readingsaudit, "run") as run:
                readingsaudit.main(["run"])
            run.assert_called_once_with(Path("/fake"), gradle=None)
            with mock.patch.object(readingsaudit, "fetch") as fetch:
                readingsaudit.main(["fetch", "--tag", TAG])
            fetch.assert_called_once_with(Path("/fake"), TAG)
            with mock.patch.object(readingsaudit, "age", return_value=1) as age, self.assertRaises(SystemExit) as stop:
                readingsaudit.main(["age"])
            self.assertEqual(1, stop.exception.code)
            self.assertEqual([readingsaudit.TABLE, readingsaudit.OLD_KANA], age.call_args.args[0])



if __name__ == "__main__":
    unittest.main()
