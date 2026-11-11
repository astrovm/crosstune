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


if __name__ == "__main__":
    unittest.main()
