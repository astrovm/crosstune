import io
import json
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import compare_sheets as c  # noqa: E402


def char(letter, left, right):
    return {"c": letter, "left": left, "right": right}


def chord(name, left, bass=None):
    return {"name": name, "bass": bass, "left": left}


def line(chars=(), chords=()):
    return {"chars": list(chars), "chords": list(chords)}


def page(*lines, font="Inconsolata, monospace"):
    return {"url": "https://example.test/a.html", "x0": 0, "font": font, "lines": list(lines)}


def words(text, width=20):
    return [char(letter, i * width, (i + 1) * width) for i, letter in enumerate(text)]


class NamesTest(unittest.TestCase):
    def test_a_chord_is_its_text_and_the_bass_after_the_link(self):
        self.assertEqual(c.chord_name(chord(" D\n", 0, "F#")), "D/F#")
        self.assertEqual(c.chord_name(chord("Am", 0)), "Am")

    def test_a_letter_outside_the_basic_plane_is_two_units(self):
        self.assertEqual([c.utf16_length(x) for x in ("a", "あ", "\U0001F600")], [1, 1, 2])


class CleanTest(unittest.TestCase):
    def test_a_link_with_no_name_draws_no_chord_and_an_empty_line_goes(self):
        cleaned = c.clean(page(line(chords=[chord(" \n ", 0)]), line(words("ab"), [chord("C", 0)])))
        self.assertEqual([[x["name"] for x in l["chords"]] for l in cleaned["lines"]], [["C"]])


class LetterAtTest(unittest.TestCase):
    def test_the_letter_that_starts_nearest(self):
        self.assertEqual(c.letter_at(words("abc"), 0, 41), 2)
        self.assertEqual(c.letter_at(words("abc"), 0, 29), 1)

    def test_halfway_goes_to_the_later_letter(self):
        self.assertEqual(c.letter_at(words("abc"), 0, 30), 2)

    def test_past_the_last_letter_is_the_end_of_the_line(self):
        self.assertEqual(c.letter_at(words("abc"), 0, 500), 3)

    def test_no_words_is_the_start(self):
        self.assertEqual(c.letter_at([], 21, 300), 0)

    def test_an_emoji_counts_for_two_units(self):
        self.assertEqual(c.letter_at(words("\U0001F600b"), 0, 20), 2)


class ExpectedTest(unittest.TestCase):
    def test_each_chord_line_takes_the_words_under_it(self):
        lines = c.expected(page(
            line(chords=[chord("C", 0), chord("G", 40, "B")]), line(words("abc")),
            line(chords=[chord("F", 20)]),
        ))
        self.assertEqual(lines, [
            {"text": "abc", "chords": [[0, "C"], [2, "G/B"]]},
            {"text": "", "chords": [[0, "F"]]},
        ])


class ComparePageTest(unittest.TestCase):
    def drawn(self):
        return page(line(chords=[chord("C", 0), chord("G", 40)]), line(words("abc")))

    def test_a_chord_on_the_right_letter(self):
        counts, wrong = c.compare_page(self.drawn(), [{"text": "abc", "chords": [[0, "C"], [2, "G"]]}])
        self.assertEqual((counts["chords compared"], counts["chords on another letter"], wrong), (2, 0, []))

    def test_a_chord_on_another_letter(self):
        counts, wrong = c.compare_page(self.drawn(), [{"text": "abc", "chords": [[0, "C"], [1, "G"]]}])
        self.assertEqual((counts["chords compared"], counts["chords on another letter"]), (2, 1))
        self.assertEqual(wrong, [("abc", [2, "G"], [1, "G"])])

    def test_other_words_are_counted_on_their_own(self):
        counts, wrong = c.compare_page(self.drawn(), [{"text": "xyz", "chords": [[0, "C"], [2, "G"]]}, {"text": "q", "chords": [[0, "A"]]}])
        self.assertEqual(counts["chord lines the app reads as other words or chords"], 1)
        self.assertEqual(counts["chords on those lines"], 2)
        self.assertEqual(counts["app chord lines the browser does not draw"], 2)
        self.assertEqual(counts["chords compared"], 0)

    def test_app_lines_with_no_chords_are_not_chord_lines(self):
        counts, _ = c.compare_page(self.drawn(), [{"text": "abc", "chords": [[0, "C"], [2, "G"]]}, {"text": "more", "chords": []}])
        self.assertEqual(counts["app chord lines the browser does not draw"], 0)


class PositionsTest(unittest.TestCase):
    def drawn(self):
        return page(line(chords=[chord("C", 0), chord("G", 40)]), line(words("abc")))

    def test_positions_keep_the_chords_and_none_of_the_words(self):
        found = c.positions([self.drawn()])
        self.assertEqual(found[0]["lines"], [{"text": c.hidden("abc"), "chords": [[0, "C"], [2, "G"]]}])
        self.assertNotIn("abc", json.dumps(found))
        self.assertEqual((found[0]["url"], found[0]["font"], found[0]["hidden"]), (self.drawn()["url"], self.drawn()["font"], True))

    def test_positions_compare_as_the_drawing_does(self):
        hidden = c.positions([self.drawn()])[0]
        for app in ([[0, "C"], [2, "G"]], [[0, "C"], [1, "G"]]):
            got = [{"text": "abc", "chords": app}, {"text": "more", "chords": []}]
            self.assertEqual(c.compare_page(hidden, got)[0], c.compare_page(self.drawn(), got)[0])

    def test_the_command_line_writes_positions_and_compares_with_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "drawing.json").write_text(json.dumps([self.drawn()]), encoding="utf-8")
            c.main(["positions", str(folder / "drawing.json"), str(folder / "positions.json")])
            (folder / "app-x.jsonl").write_text(json.dumps({"url": self.drawn()["url"], "lines": [{"text": "abc", "chords": [[0, "C"], [1, "G"]]}]}) + "\n", encoding="utf-8")
            with unittest.mock.patch("sys.stdout", new=io.StringIO()) as out:
                c.main(["compare", str(folder), "x", "--truth", str(folder / "positions.json")])
            self.assertIn("      1  chords on another letter", out.getvalue())


class CompareTest(unittest.TestCase):
    def test_the_totals_of_every_sheet(self):
        drawn = page(line(chords=[chord("C", 0)]), line(words("ab")))
        app = {drawn["url"]: {"url": drawn["url"], "mono": False, "lines": [{"text": "ab", "chords": [[1, "C"]]}]}}
        total, examples = c.compare([drawn, {**drawn, "url": "https://example.test/b.html"}], app)
        self.assertEqual(total["sheets compared"], 1)
        self.assertEqual(total["sheets the app did not answer"], 1)
        self.assertEqual(total["sheets whose font stack the app misreads"], 1)
        self.assertEqual(total["sheets with a chord on another letter"], 1)
        self.assertEqual(examples, [(drawn["url"], "ab", [0, "C"], [1, "C"])])

    def test_read_app_keys_each_row_by_its_sheet(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "app.jsonl"
            path.write_text(json.dumps({"url": "u", "lines": []}) + "\n", encoding="utf-8")
            self.assertEqual(c.read_app(path), {"u": {"url": "u", "lines": []}})


class MovesTest(unittest.TestCase):
    def drawn(self, widths, left=60, url="https://example.test/a.html", font="Inconsolata, monospace"):
        chars, x = [], 0
        for letter, width in zip("abcd", widths):
            chars.append(char(letter, x, x + width))
            x += width
        return {**page(line(chords=[chord("C", left)]), line(chars), font=font), "url": url}

    def test_the_same_drawing_moves_nothing(self):
        counts, shifts, culprits = c.moves([self.drawn([20, 20, 20, 20])], [self.drawn([20, 20, 20, 20])])
        self.assertEqual(counts["chords (mono stack)"], 1)
        self.assertEqual(counts["chords on another letter (mono stack)"], 0)
        self.assertEqual(counts["chords on a letter start in A (mono stack)"], 1)
        self.assertEqual((shifts, culprits), ({}, {}))

    def test_a_wider_letter_before_the_chord_moves_it_back_a_letter(self):
        counts, shifts, culprits = c.moves([self.drawn([20, 20, 20, 20])], [self.drawn([20, 44, 20, 20], left=60)])
        self.assertEqual(counts["chords on another letter (mono stack)"], 1)
        self.assertEqual(counts["sheets with a chord on another letter (mono stack)"], 1)
        self.assertEqual(counts["chords on a letter start in B (mono stack)"], 0)
        self.assertEqual(shifts, {("mono", -1.0): 1})
        self.assertEqual(culprits, {("mono", ord("b")): 1})

    def test_other_words_are_not_compared(self):
        b = self.drawn([20, 44, 20, 20])
        b["lines"][1]["chars"][3]["c"] = "x"
        counts, _, _ = c.moves([self.drawn([20] * 4)], [b])
        self.assertEqual(counts["chords (mono stack)"], 0)

    def test_a_leading_space_one_drawing_leaves_out_blames_no_letter(self):
        a = self.drawn([20, 44, 20, 20], left=20)
        a["lines"][1]["chars"].insert(0, char(" ", -20, 0))
        counts, _, culprits = c.moves([a], [self.drawn([20] * 4, left=20)])
        self.assertEqual(counts["chords on another letter (mono stack)"], 1)
        self.assertEqual(culprits, {})

    def test_a_sheet_only_one_drawing_has_is_counted_on_its_own(self):
        counts, _, _ = c.moves([self.drawn([20] * 4, url="https://example.test/b.html", font="x")], [self.drawn([20] * 4)])
        self.assertEqual(counts["sheets only A drew"], 1)

    def test_starts_are_by_utf16_unit_and_the_end_counts(self):
        self.assertEqual(c.starts(words("a\U0001F600")), {0: 0, 1: 20, 3: 40})
        self.assertEqual(c.starts([]), {})

    def test_moves_main_prints_the_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b = Path(folder) / "a.json", Path(folder) / "b.json"
            a.write_text(json.dumps([self.drawn([20] * 4)]), encoding="utf-8")
            b.write_text(json.dumps([self.drawn([20, 44, 20, 20])]), encoding="utf-8")
            c.moves_main(a, b, 5)


class TemplateTest(unittest.TestCase):
    def test_old_takes_the_font_stack_and_windows_31j_out_of_the_template(self):
        source = c.old_template(c.TEMPLATE.read_text(encoding="utf-8"))
        self.assertNotIn("jTotalMonospace", source)
        self.assertNotIn("mono", source.split("fun json")[0].split("val lines")[1])
        self.assertIn('"Shift_JIS"', source)

    def test_a_template_without_a_part_old_takes_out_fails(self):
        with self.assertRaises(ValueError):
            c.old_template("class CrawlDumpTest")


if __name__ == "__main__":
    unittest.main()
