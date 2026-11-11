import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import golden  # noqa: E402
import readingsaudit  # noqa: E402


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
    """GoldenContexts as a made-up app answers it: the same search as GoldenReadings.Around, in Python, on [read]."""

    def __init__(self, read):
        self.read = read
        self.jobs = []

    def __call__(self, folder, job):
        self.jobs.append(job)
        rows = [[self.ruby_at(r[0], r[1], r[2], d) == r[3] if r[4] in (d, "both") else None for d in ("ipadic", "unidic")] for r in job["rows"]]
        return {"rows": rows, "cases": [self.case(*case, set(job["lines"]), job["width"]) for case in job["cases"]]}

    def ruby_at(self, stretch, word, index, dictionary):
        parts = self.read(stretch, dictionary)
        starts = golden.find_starts(stretch, word)
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
        return golden.ruby_over(parts, start, start + len(word))

    def case(self, line, word, occurrence, ruby, dictionary, spans, lines, width):
        """Spans in and out in UTF-16, as Kotlin counts them."""
        spans = [(golden.letters(line, a), golden.letters(line, b)) for a, b in spans]
        start = golden.find_starts(line, word)[occurrence]
        end, memo = start + len(word), {}

        def good(a, b):
            stretch = line[a:b]
            starts = golden.find_starts(stretch, word)
            return self.ruby_at(stretch, word, starts.index(start - a) if len(starts) > 1 else None, dictionary) == ruby

        def stable(a, b):
            if (a, b) not in memo:
                memo[(a, b)] = (a == 0 or stable(a - 1, b)) and (b == len(line) or stable(a, b + 1)) and good(a, b)
            return memo[(a, b)]

        def fits(a, b):
            return line[a] != "#" and not set(line[a:b]) & golden.LINE_ENDS

        def around(lengths):
            for length in lengths:
                for a in range(max(0, end - length), start + 1):
                    b = a + length
                    if b <= len(line) and fits(a, b):
                        yield a, b

        def utf16(span):
            return span and [golden.units(line, span[0]), golden.units(line, span[1])]
        stretch = next((span for span in around(range(len(word), len(line) + 1)) if stable(*span)), None)
        for _ in range(width if stretch else 0):
            a, b = stretch
            a = a - 1 if a > 0 and fits(a - 1, b) else a
            stretch = (a, b + 1 if b < len(line) and fits(a, b + 1) else b)
        fallback = None
        if stretch == (0, len(line)):
            shorter = [span for span in around(range(len(line) - 1, len(word) - 1, -1)) if line[span[0]:span[1]] not in lines]
            fallback = next((span for span in shorter if stable(*span)), None) or next((span for span in shorter if good(*span)), None)
        return {"stretch": utf16(stretch), "whole": good(0, len(line)), "fallback": utf16(fallback),
                "spans": [[good(a, b), stable(a, b)] for a, b in spans]}


def stretch_reader(base, wrong):
    """[base], but 日 is ひ, not び, in each stretch of [wrong]: a made-up rule that fires only on more of the line."""
    def read(stretch, dictionary):
        parts = base(stretch, dictionary)
        return [[text, "ひ" if text == "日" and stretch in wrong else reading] for text, reading in parts]
    return read


# The made-up app's words. 日 is び after 曜, and the second 丙 of two is てい. UniDic reads 丁戊 otherwise.
WORDS = {"月": "げつ", "曜": "よう", "日": "ひ", "丙": "へい", "甲乙": "こうおつ", "丁戊": "ていぼ", "己庚": "きこう"}
APP = lexicon_reader({"ipadic": WORDS, "unidic": {**WORDS, "丁戊": "ちょうぼ"}}, {("曜", "日"): "び", ("丙", "丙"): "てい"})


def golden_rows(dest):
    return [line.split("\t") for line in dest.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]


class Golden(unittest.TestCase):

    def run_golden(self, folder, dest, accept=(), app=APP, width=0, union_share=None):
        """golden with [width] letters of context: 0 shows the stable stretch itself."""
        fake = FakeContexts(app)
        with mock.patch("builtins.print") as said:
            code = golden.golden(folder, dest, accept, fake, width, union_share)
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
            self.assertEqual(golden.GOLDEN_HEADER + ["# rows: 7"], lines[:len(golden.GOLDEN_HEADER) + 1])
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
            golden.write_golden(dest, [("月曜日", "日", "", "び", "日=び", "both")])
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
            golden.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "unidic")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertEqual([["甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both"]], golden_rows(dest))

    def test_a_reading_a_wider_stretch_reads_otherwise_is_locked_where_no_wider_one_does(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("今曜日だ", [["今", None], ["曜", "よう"], ["日", "び"], ["だ", None]])])
            dest = folder / "golden.tsv"
            # 曜日 reads び, but 今曜日 does not: a row on 曜日 would miss a change that breaks only that. 曜日だ and the line read び.
            code, said, fake = self.run_golden(folder, dest, app=stretch_reader(APP, {"今曜日"}))
            self.assertEqual(0, code, said)
            self.assertIn(["曜日だ", "日", "", "び", "日=び", "both"], golden_rows(dest))
            self.assertNotIn(["曜日", "日", "", "び", "日=び", "both"], golden_rows(dest))
            self.assertIn("0 not fully guarded", said)
            # Every lyric line goes to the app, so a stretch it locks on instead of a whole line is none of them.
            self.assertEqual(["今曜日だ"], fake.jobs[0]["lines"])

    def test_a_row_already_there_on_a_stretch_a_wider_one_reads_otherwise_gets_a_row_that_holds(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("今曜日だ", [["今", None], ["曜", "よう"], ["日", "び"], ["だ", None]])])
            dest = folder / "golden.tsv"
            golden.write_golden(dest, [("曜日", "日", "", "び", "日=び", "both")])
            code, said, fake = self.run_golden(folder, dest, app=stretch_reader(APP, {"今曜日"}))
            self.assertEqual(0, code, said)
            # The old row stays, and the stretch it is tried on is the one in the line.
            self.assertIn(["曜日", "日", "", "び", "日=び", "both"], golden_rows(dest))
            self.assertIn(["曜日だ", "日", "", "び", "日=び", "both"], golden_rows(dest))
            self.assertIn(["今曜日だ", "日", 0, "日=び", "ipadic", [[1, 3]]], fake.jobs[0]["cases"])

    def test_a_song_keeps_at_most_a_quarter_of_its_lines_whole(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            plain = [["月", "げつ"]]
            write_audit(folder, [
                audit_line("月曜日に", [["月", "げつ"], ["曜", "よう"], ["日", "び"], ["に", None]]),
                audit_line("曜日に月", [["曜", "よう"], ["日", "び"], ["に", None], ["月", "げつ"]]),
                audit_line("月", plain), audit_line("月に", [["月", "げつ"], ["に", None]]),
                # A line the audit has no ruby for is still the song's.
                {"text": "その", "builtIn": [], "uniDic": [], "truth": []},
            ])
            dest = folder / "golden.tsv"
            # 日 is び on each whole line and on 曜日, and ひ on every other stretch: only a whole line holds it.
            app = stretch_reader(APP, {"月曜日", "曜日に"})
            code, said, _fake = self.run_golden(folder, dest, app=app)
            self.assertEqual(0, code, said)
            rows = golden_rows(dest)
            # Five lines: one may be in the file whole. 月 already is, as its own row.
            self.assertIn(["月", "月", "", "げつ", "月=げつ", "both"], rows)
            self.assertNotIn("月曜日に", [row[0] for row in rows])
            self.assertNotIn("曜日に月", [row[0] for row in rows])
            self.assertIn(["曜日", "日", "", "び", "日=び", "both"], rows)
            self.assertIn("not fully guarded: 日\tび\tipadic", said)
            self.assertIn("4 not fully guarded", said)
            self.assertIn("golden: 1 whole lyric lines, at most 1 of a song's 5 lines", said)
            first = dest.read_bytes()
            code, said, _fake = self.run_golden(folder, dest, app=app)
            self.assertEqual(0, code, said)
            self.assertEqual(first, dest.read_bytes())
            self.assertIn("0 new", said)

    def test_a_row_holds_letters_of_its_line_each_side_of_its_stable_stretch_as_far_as_a_row_can(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [
                audit_line("あい曜日うえお", [["あい", None], ["曜", "よう"], ["日", "び"], ["うえお", None]]),
                # A row never starts with # nor holds a tab: there the stretch stops short.
                audit_line("#曜日だ\tよ", [["#", None], ["曜", "よう"], ["日", "び"], ["だ\tよ", None]]),
            ])
            dest = folder / "golden.tsv"
            code, said, fake = self.run_golden(folder, dest, width=2)
            self.assertEqual(0, code, said)
            self.assertEqual([
                ["あい曜日う", "曜", "", "よう", "曜=よう", "both"],
                ["あい曜日うえ", "日", "", "び", "日=び", "both"],
                ["曜日だ", "日", "", "び", "日=び", "both"],
                ["曜日だ", "曜", "", "よう", "曜=よう", "both"],
            ], golden_rows(dest))
            self.assertEqual(2, fake.jobs[0]["width"])
            first = dest.read_bytes()
            code, said, _fake = self.run_golden(folder, dest, width=2)
            self.assertEqual(0, code, said)
            self.assertEqual(first, dest.read_bytes())
            self.assertIn("golden: 4 rows, 0 new", said)

    def test_a_row_already_there_locks_a_reading_only_when_it_holds_all_of_its_stretch(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("あい曜日うえお", [["あい", None], ["曜", "よう"], ["日", "び"], ["うえお", None]])])
            dest = folder / "golden.tsv"
            # 曜日 is the stable stretch, but too short for two letters each side: a row that holds them joins it.
            # あい曜日うえ holds all of 曜's: it locks 曜, and no new row joins it.
            golden.write_golden(dest, [("曜日", "日", "", "び", "日=び", "both"), ("あい曜日うえ", "曜", "", "よう", "曜=よう", "both")])
            code, said, _fake = self.run_golden(folder, dest, width=2)
            self.assertEqual(0, code, said)
            self.assertEqual([
                ["あい曜日うえ", "日", "", "び", "日=び", "both"],
                ["あい曜日うえ", "曜", "", "よう", "曜=よう", "both"],
                ["曜日", "日", "", "び", "日=び", "both"],
            ], golden_rows(dest))

    def test_a_row_the_quarter_limit_keeps_off_its_whole_line_is_short_of_it_and_not_fully_guarded(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("月曜日", [["月", "げつ"], ["曜", "よう"], ["日", "び"]])])
            dest = folder / "golden.tsv"
            # Two letters each side make each row the whole line, the song's only one. Each goes on a stable stretch short of it.
            code, said, _fake = self.run_golden(folder, dest, width=2)
            self.assertEqual(0, code, said)
            self.assertEqual([
                ["曜日", "日", "", "び", "日=び", "both"],
                ["月曜", "曜", "", "よう", "曜=よう", "both"],
                ["月曜", "月", "", "げつ", "月=げつ", "both"],
            ], golden_rows(dest))
            self.assertIn("not fully guarded: 日\tび\tunidic", said)
            self.assertIn("6 not fully guarded", said)
            first = dest.read_bytes()
            code, said, _fake = self.run_golden(folder, dest, width=2)
            self.assertEqual(first, dest.read_bytes())
            self.assertIn("golden: 3 rows, 0 new", said)

    def test_a_letter_past_u_ffff_counts_as_two_where_kotlin_counts(self):
        self.assertEqual(3, golden.units("𠮷曜日", 2))
        self.assertEqual(2, golden.letters("𠮷曜日", 3))
        self.assertEqual(0, golden.letters("𠮷曜日", 0))
        self.assertEqual(None, golden.span_in("𠮷曜日", None))
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("𠮷曜日", [["𠮷", None], ["曜", "よう"], ["日", "び"]])])
            dest = folder / "golden.tsv"
            golden.write_golden(dest, [("𠮷曜日", "日", "", "び", "日=び", "both")])
            code, said, fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            # The row already there is tried on the stretch Kotlin counts, and the new row is the stretch the app read.
            self.assertIn(["𠮷曜日", "日", 0, "日=び", "ipadic", [[0, 4]]], fake.jobs[0]["cases"])
            self.assertIn(["曜", "曜", "", "よう", "曜=よう", "both"], golden_rows(dest))

    def test_the_golden_command_takes_a_width_or_the_one_the_file_is_made_with(self):
        self.assertIsNone(golden.UNION_SHARE)
        for argv, width, share in ((["golden"], golden.CONTEXT, None), (["golden", "--width", "0", "--union-share", "0.25"], 0, 0.25)):
            with mock.patch.object(readingsaudit, "data_dir", return_value=Path("/fake")), \
                    mock.patch.object(golden, "golden", return_value=0) as made, self.assertRaises(SystemExit):
                readingsaudit.main(argv)
            made.assert_called_once_with(Path("/fake"), golden.GOLDEN_FILE, [], width=width, union_share=share)

    def test_a_song_with_room_keeps_the_whole_lines_that_guard_the_most(self):
        placed = {
            ("甲", "a", 0, "ipadic"): [("甲",) * 5, ("a",) * 5],
            ("甲", "a", 0, "unidic"): [("甲",) * 5, ("a",) * 5],
            ("乙", "b", 0, "ipadic"): [("乙",) * 5, ("b",) * 5],
            ("丙", "c", 0, "ipadic"): [("丙",) * 5, None],
        }
        songs = {"s": {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛"}}
        # Two of eight lines. 丙 can't move, so 甲, which guards more than 乙, takes the room left.
        moved = golden.cap_whole_lines(songs, set(), placed)
        self.assertEqual({("乙", "b", 0, "ipadic")}, moved)
        self.assertEqual([("b",) * 5, None], placed[("乙", "b", 0, "ipadic")])
        self.assertEqual(("甲",) * 5, placed[("甲", "a", 0, "ipadic")][0])
        # A line already in the file counts, and stays.
        placed = {("甲", "a", 0, "ipadic"): [("甲",) * 5, ("a",) * 5]}
        self.assertEqual({("甲", "a", 0, "ipadic")}, golden.cap_whole_lines(songs, {("乙",) * 5, ("丁",) * 5}, placed))

    def test_the_summary_counts_whole_lines_and_the_stretches(self):
        rows = [("甲乙", "甲", "", "", "", "both"), ("甲", "甲", "", "", "", "both"), ("丙丁戊", "丙", "", "", "", "both")]
        # 甲乙丙丁戊 is no row, but two rows spell it. A song with one of its five lines spelled is not over a quarter,
        # nor is one with one of four.
        self.assertEqual("1 whole lyric lines, at most 1 of a song's 2 lines; 2 of 10 lines spelled end to end by the rows together, "
                         "1 of 4 songs over a quarter that way; stretches median 2 and longest 3 characters",
                         golden.summary(rows, {"a": {"甲乙", "己"}, "b": {"x", "y", "z", "丙", "甲乙丙丁戊"}, "c": set(),
                                               "d": {"甲乙", "p", "q", "r"}}))
        self.assertEqual("0 whole lyric lines, at most 0 of a song's 0 lines; 0 of 0 lines spelled end to end by the rows together, "
                         "0 of 0 songs over a quarter that way; stretches median 0 and longest 0 characters",
                         golden.summary([], {}))

    def test_a_line_is_spelled_when_the_stretches_together_hold_each_letter_where_it_is(self):
        self.assertTrue(golden.spelled("甲乙丙", {"甲乙", "乙丙"}, 2))
        self.assertTrue(golden.spelled("甲乙甲", {"甲", "乙"}, 1))
        self.assertFalse(golden.spelled("甲乙丙", {"甲乙", "丙丁"}, 2))
        self.assertFalse(golden.spelled("甲乙丙", {"甲乙", "丙乙"}, 2))
        self.assertEqual({"s": {"甲乙"}}, golden.spelled_lines({"甲", "乙"}, {"s": {"甲乙", "丙", ""}}))
        self.assertEqual([{"甲"}, {"乙甲"}, {"甲", "乙甲"}], golden.holders("甲乙甲", {"甲", "乙甲", "丙"}))

    def test_a_song_over_the_union_share_loses_the_new_rows_over_its_cheapest_letter(self):
        def placed():
            return {("甲乙", "甲乙", 0, "ipadic"): [("甲乙",) * 5, None], ("甲", "甲", 0, "ipadic"): [("甲",) * 5, None],
                    ("乙", "乙", 0, "ipadic"): [("乙",) * 5, None], ("丙丁", "丙", 0, "ipadic"): [("丙丁",) * 5, None]}
        songs = {"s": {"甲乙", "丙丁", "戊", "己"}}
        # No limit: nothing goes.
        rows = placed()
        self.assertEqual(set(), golden.cap_union(songs, set(), rows, None))
        self.assertEqual(placed(), rows)
        # One of four lines may be spelled. 丙丁 has a letter one stretch holds; each of 甲乙's is held by two.
        rows = placed()
        self.assertEqual({("丙丁", "丙", 0, "ipadic")}, golden.cap_union(songs, set(), rows, 0.25))
        self.assertEqual(3, len(rows))
        # A row already in the file stays, so 甲乙 is cut where it is held by the fewest new rows: 乙 and 甲乙.
        rows = placed()
        del rows[("丙丁", "丙", 0, "ipadic")]
        self.assertEqual({("甲乙", "甲乙", 0, "ipadic"), ("乙", "乙", 0, "ipadic")}, golden.cap_union(songs, {("丙丁",) * 5}, rows, 0.25))
        self.assertEqual([("甲", "甲", 0, "ipadic")], list(rows))
        # Lines the file already spells stay spelled: there is nothing new to take away.
        rows = {}
        self.assertEqual(set(), golden.cap_union(songs, {("甲乙",) * 5, ("丙丁",) * 5}, rows, 0.25))

    def test_the_golden_command_holds_a_song_to_the_union_share_and_names_what_it_leaves_unguarded(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("月曜日", [["月", "げつ"], ["曜", "よう"], ["日", "び"]])])
            dest = folder / "golden.tsv"
            code, said, _fake = self.run_golden(folder, dest, union_share=0.25)
            self.assertEqual(0, code, said)
            # The song's one line may not be spelled: 日, the first of three letters each held by one row, goes.
            self.assertEqual(["曜", "月"], [row[0] for row in golden_rows(dest)])
            self.assertIn("not guarded, its song spelled: 日\tび\tipadic", said)
            self.assertIn("4 of 6 right readings locked (66.7%), 0 not fully guarded, 2 not guarded as their song would be spelled", said)
            self.assertIn("0 of 1 lines spelled end to end", said)

    def test_a_reading_both_limits_move_is_named_once_as_not_guarded(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("月曜日", [["月", "げつ"], ["曜", "よう"], ["日", "び"]])])
            dest = folder / "golden.tsv"
            # The quarter moves each row off the whole line, to 月曜 and 曜日, which spell it together: 曜日 goes.
            code, said, _fake = self.run_golden(folder, dest, width=2, union_share=0.25)
            self.assertEqual(0, code, said)
            self.assertEqual(["月曜", "月曜"], [row[0] for row in golden_rows(dest)])
            self.assertIn("not guarded, its song spelled: 日\tび\tunidic", said)
            self.assertNotIn("not fully guarded: 日", said)
            self.assertIn("4 of 6 right readings locked (66.7%), 4 not fully guarded, 2 not guarded as their song would be spelled", said)

    def test_the_hole_counts_readings_no_row_guards_and_breaks_no_row_catches(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_audit(folder, [audit_line("今曜日だ", [["今", None], ["曜", "よう"], ["日", "び"], ["だ", None]])])
            short, stable = folder / "short.tsv", folder / "stable.tsv"
            golden.write_golden(short, [("曜日", "日", "", "び", "日=び", "both"), ("曜", "曜", "", "よう", "曜=よう", "both")])
            # The stable file holds the short row too: a case is caught when any row it has fails.
            golden.write_golden(stable, [("曜日だ", "日", "", "び", "日=び", "both"), ("曜日", "日", "", "び", "日=び", "both"),
                                                ("曜", "曜", "", "よう", "曜=よう", "both")])

            def measure(wrong):
                with mock.patch("builtins.print") as said:
                    self.assertEqual(0, golden.hole(folder, [short, stable], FakeContexts(stretch_reader(APP, wrong))))
                return [call.args[0] for call in said.call_args_list]
            # 今曜日 reads 日 otherwise: the short row misses it, in both dictionaries.
            self.assertEqual([
                f"hole: {short}: 2 rows, 0 fail. Of 4 right readings, 2 read otherwise on some wider stretch than every row they have, "
                "or have none. 0 read otherwise on their whole line, and 0 of those have no row that fails.",
                f"hole: {stable}: 3 rows, 0 fail. Of 4 right readings, 0 read otherwise on some wider stretch than every row they have, "
                "or have none. 0 read otherwise on their whole line, and 0 of those have no row that fails.",
            ], measure({"今曜日"}))
            # A break on the whole line that 曜日 alone still reads right: only the stable row fails.
            said = measure({"今曜日", "今曜日だ", "曜日だ"})
            self.assertIn("2 rows, 0 fail.", said[0])
            self.assertIn("2 read otherwise on their whole line, and 2 of those have no row that fails.", said[0])
            self.assertIn("3 rows, 1 fail.", said[1])
            self.assertIn("2 read otherwise on their whole line, and 0 of those have no row that fails.", said[1])

    def test_the_hole_command_measures_the_files_it_is_given_or_the_one_in_the_repo(self):
        with mock.patch.object(readingsaudit, "data_dir", return_value=Path("/fake")), \
                mock.patch.object(golden, "hole", return_value=0) as hole, self.assertRaises(SystemExit):
            readingsaudit.main(["hole"])
        hole.assert_called_once_with(Path("/fake"), [golden.GOLDEN_FILE])
        with mock.patch.object(readingsaudit, "data_dir", return_value=Path("/fake")), \
                mock.patch.object(golden, "hole", return_value=0) as hole, self.assertRaises(SystemExit):
            readingsaudit.main(["hole", "--golden", "/a.tsv", "--golden", "/b.tsv"])
        hole.assert_called_once_with(Path("/fake"), [Path("/a.tsv"), Path("/b.tsv")])

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
            golden.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")])
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
            golden.write_golden(dest, [("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")])
            code, said, _fake = self.run_golden(folder, dest)
            self.assertEqual(0, code, said)
            self.assertIn("0 of 0 right readings locked (none)", said)

    def test_no_audit_stops(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(SystemExit):
                self.run_golden(Path(folder), Path(folder) / "golden.tsv")

    def test_a_row_is_six_whole_fields_that_read_back_as_written(self):
        good = ("思い出", "思い出", "", "おもいで", "思=おも|い|出=で", "both")
        golden.check_fields(good)
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
                golden.check_fields(bad)
        self.assertTrue(golden.writable("思い出", "思=おも|い|出=で"))
        self.assertFalse(golden.writable("思=出", "思=おも|=|出=で"))
        self.assertFalse(golden.writable("思い出", "思=おも\n|い|出=で"))

    def test_a_broken_row_in_the_file_stops_the_command(self):
        with tempfile.TemporaryDirectory() as folder:
            dest = Path(folder) / "golden.tsv"
            self.assertEqual([], golden.read_golden(dest))
            dest.write_text("# rows: 1\r\n甲乙\t甲乙\t\tこうおつ\t甲乙=こうおつ\tboth\r\n\n", encoding="utf-8")
            self.assertEqual([("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")], golden.read_golden(dest))
            dest.write_text("甲乙\t甲乙\t\tこうおつ\tboth\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                golden.read_golden(dest)

    def test_a_row_names_an_occurrence_its_stretch_has(self):
        self.assertEqual((1, 3), golden.span_of(("曜日", "日", "", "び", "日=び", "both"), "月曜日に", "日", 2))
        self.assertIsNone(golden.span_of(("曜日", "日", "", "び", "日=び", "both"), "日曜日", "日", 0))
        self.assertIsNone(golden.span_of(("日日", "日", "2", "ひ", "日=ひ", "both"), "日日", "日", 0))
        self.assertIsNone(golden.span_of(("曜日", "日", "", "び", "日=び", "both"), "月曜日に", "日", 1))

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
                self.assertEqual(answer, golden.golden_contexts(folder, {"rows": [], "cases": [["日", "日", 0, "日=ひ", "ipadic"]]}))
            # The songs' lines go once they are answered.
            self.assertEqual([], list(folder.iterdir()))
            # A failed run, or one that wrote nothing, stops the command: an old answer is never read.
            with mock.patch.object(readingsaudit, "songcheck", return_value=1), self.assertRaises(SystemExit):
                golden.golden_contexts(folder, {"rows": [], "cases": []})
            self.assertEqual([], list(folder.iterdir()))
            with mock.patch.object(readingsaudit, "songcheck", return_value=0), self.assertRaises(SystemExit):
                golden.golden_contexts(folder, {"rows": [], "cases": []})

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
        with mock.patch.object(sys, "argv", argv), mock.patch.object(golden, "golden", return_value=0) as locking, \
                self.assertRaises(SystemExit) as stop:
            readingsaudit.main()
        self.assertEqual(0, stop.exception.code)
        self.assertEqual([["甲乙", "こうおつ"]], locking.call_args.args[2])
        self.assertEqual(Path("/x.tsv"), locking.call_args.args[1])

    def test_the_committed_file_has_the_header_a_remake_writes_and_its_count(self):
        text = golden.GOLDEN_FILE.read_text(encoding="utf-8")
        rows = golden.read_golden(golden.GOLDEN_FILE)
        header = [line for line in text.splitlines() if line.startswith("#")]
        self.assertEqual(golden.GOLDEN_HEADER + [f"# rows: {len(rows)}"], header)
        self.assertGreater(len(rows), 0)
        self.assertEqual(sorted(rows, key=golden.row_sort_key), rows)


if __name__ == "__main__":
    unittest.main()
