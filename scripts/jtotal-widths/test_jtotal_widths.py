import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jtotal_widths as w  # noqa: E402


class RunsTest(unittest.TestCase):
    def test_letters_of_one_width_side_by_side_are_one_run(self):
        self.assertEqual(w.runs({0x100: 1000, 0x101: 1000, 0x102: 1000}, set()), [[0x100, 0x102, 1000]])

    def test_a_new_width_starts_a_new_run(self):
        self.assertEqual(w.runs({0x100: 1000, 0x101: 888, 0x102: 888}, set()), [[0x100, 0x100, 1000], [0x101, 0x102, 888]])

    def test_two_columns_is_what_the_app_assumes_so_it_is_not_listed(self):
        self.assertEqual(w.runs({0x100: 2000, 0x101: 1000}, set()), [[0x101, 0x101, 1000]])

    def test_a_run_goes_on_across_a_code_point_with_no_letter(self):
        self.assertEqual(w.runs({0x100: 1000, 0x101: 2000, 0x102: 1000}, {0x101}), [[0x100, 0x102, 1000]])

    def test_a_run_does_not_cross_a_gap_that_has_a_letter_in_it(self):
        self.assertEqual(w.runs({0x100: 1000, 0x102: 1000}, set()), [[0x100, 0x100, 1000], [0x102, 0x102, 1000]])

    def test_a_code_point_with_no_letter_starts_no_run(self):
        self.assertEqual(w.runs({0x100: 1000, 0x101: 500, 0x102: 1000}, {0x101}), [[0x100, 0x102, 1000]])


class LinesTest(unittest.TestCase):
    def test_a_run_of_one_is_one_code_point(self):
        self.assertEqual(w.lines([[0x133, 0x133, 888], [0x1E00, 0x1E9F, 1000]]), ["133 888", "1E00-1E9F 1000"])

    def test_header_records_the_environment_as_comments(self):
        self.assertEqual(w.header(["Chromium 123", "font 1"]), [
            "# J-Total widths, in thousandths of a digit", "# Chromium 123", "# font 1"
        ])

    def test_only_installed_fonts_are_recorded(self):
        self.assertEqual(w.installed_fonts("fonts-one 1 installed\nfonts-two 2 not-installed\n"), ["fonts-one 1"])


class SpansTest(unittest.TestCase):
    def test_the_controls_above_ascii_are_left_out(self):
        codes = w.spans_codes()
        self.assertNotIn(0x85, codes)
        self.assertIn(0xA0, codes)
        self.assertIn(0x1F600, codes)

    def test_the_surrogates_are_not_letters(self):
        self.assertNotIn(0xD800, w.spans_codes())

    def test_unassigned_code_points_are_told_apart(self):
        self.assertTrue(w.unassigned(0x378))
        self.assertFalse(w.unassigned(0x3B1))



class ProbeTest(unittest.TestCase):
    def test_a_letter_shift_jis_holds_is_written_as_itself(self):
        self.assertEqual(w.written(0x3042), "あ")
        self.assertEqual(w.written(0xFF9E), "ﾞ")

    def test_any_other_letter_is_a_numeric_reference_as_the_page_must_write_it(self):
        self.assertEqual(w.written(0x133), "&#307;")
        self.assertEqual(w.written(0x3042, as_entity={0x3042}), "&#12354;")

    def test_the_page_is_shift_jis_as_j_totals_is(self):
        self.assertEqual(w.shift_jis_page("あ&#307;"), "あ&#307;".encode("cp932"))

    def test_each_letter_is_measured_ten_times_between_base_letters_against_eleven_of_them_alone(self):
        rows = w.probe_rows([0xFF9E, 0x133, 0x301])
        for base in ("ｶ", "あ", "a"):
            self.assertIn(f'<tt data-base="{base}">{base * 11}</tt>', rows)
        self.assertIn('<tt data-c="65438" data-b="ｶ">ｶ<span>ﾞ</span>ｶ' + "ﾞｶ" * 9 + "</tt>", rows)
        self.assertIn('<tt data-c="307" data-b="あ">あ<span>&#307;</span>あ' + "&#307;あ" * 9 + "</tt>", rows)
        self.assertIn('<tt data-c="769" data-b="a">a<span>&#769;</span>a' + "&#769;a" * 9 + "</tt>", rows)

    def test_a_width_is_a_tenth_of_what_ten_of_the_letter_add(self):
        self.assertIn("(tt.getBoundingClientRect().width - base[tt.dataset.b]) / REPEATS / digit * 1000", w.PROBE_JS)
        self.assertEqual(w.REPEATS, 10)

    def test_a_mark_is_measured_after_a_letter_it_is_written_after(self):
        self.assertEqual([w.base_letter(c) for c in (0xFF9E, 0xFF9F, 0x3099, 0x309A, 0x301, 0x20DD, 0x3042, 0x203B)],
                         ["ｶ", "ｶ", "か", "か", "a", "a", "あ", "あ"])

    def test_a_mark_of_a_script_is_measured_after_a_consonant_of_that_script(self):
        marks = {
            0x093F: "क",  # DEVANAGARI VOWEL SIGN I, spacing, drawn before the consonant
            0x0941: "क",  # DEVANAGARI VOWEL SIGN U
            0x0BCD: "க",  # TAMIL SIGN VIRAMA
            0x0E48: "ก",  # THAI CHARACTER MAI EK
            0x064E: "ب",  # ARABIC FATHA, after BEH, as Arabic has no KA
            0x05B8: "א",  # HEBREW POINT QAMATS
            0x0F71: "ཀ",  # TIBETAN VOWEL SIGN AA
            0x1CD0: "क",  # VEDIC TONE KARSHANA, written in Devanagari
            0x302A: "あ",  # IDEOGRAPHIC LEVEL TONE MARK, written after a kanji
            0xFE0F: "a",  # VARIATION SELECTOR-16, of no script
        }
        self.assertEqual({c: w.base_letter(c) for c in marks}, marks)

    def test_a_base_shift_jis_cannot_hold_is_a_reference_too(self):
        rows = w.probe_rows([0x93F])
        self.assertIn('<tt data-base="&#2325;">' + "&#2325;" * 11 + "</tt>", rows)
        self.assertIn('<tt data-c="2367" data-b="&#2325;">&#2325;<span>&#2367;</span>&#2325;', rows)
        w.shift_jis_page(rows)

    def test_every_mark_s_base_is_a_letter_and_not_a_mark(self):
        import unicodedata
        for code in range(0x80, 0x30000):
            if unicodedata.category(chr(code)) in ("Mn", "Mc", "Me"):
                self.assertTrue(unicodedata.category(w.base_letter(code)).startswith("L"), f"U+{code:04X}")

    def test_the_trim_probe_pairs_marks_and_puts_each_at_both_ends_of_a_line(self):
        rows = w.trim_rows(marks="」「", neighbours="あ")
        for pair in ("」「", "」あ", "あ「", "」」"):
            self.assertIn(f'data-pair="{pair}">あ{pair}あ<', rows)
        self.assertNotIn('data-pair="ああ"', rows)
        self.assertIn('<tt data-first="「">「あ</tt>', rows)
        self.assertIn('<tt data-later="「">あ<br>「あ</tt>', rows)
        self.assertIn('<tt data-last="」">あ」</tt>', rows)


class TablesTest(unittest.TestCase):
    def measurement(self, widths):
        return {"widths": {str(c): v for c, v in widths.items()}, "nothing": [], "environment": ["Chromium 1"]}

    def test_a_table_names_where_it_was_measured_first(self):
        text = w.table_text(self.measurement({0xFF9E: 1000, 0x3042: 2000}), "Measured on Windows")
        self.assertEqual(text, "# J-Total widths, in thousandths of a digit\n# Measured on Windows\n# Chromium 1\nFF9E 1000\n")

    def test_generate_writes_both_tables_and_check_then_agrees(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "SOURCE.txt").write_text("Measured on Windows\n", encoding="utf-8")
            (tmp / "main.json").write_text(json.dumps(self.measurement({0x133: 1000})), encoding="utf-8")
            (tmp / "mono.json").write_text(json.dumps(self.measurement({0x133: 1105})), encoding="utf-8")
            with mock.patch.object(w, "TABLE", tmp / "out" / "a.txt"), mock.patch.object(w, "MONO_TABLE", tmp / "out" / "b.txt"), \
                    mock.patch.object(w, "REPO", tmp), mock.patch("builtins.print"):
                (tmp / "out").mkdir()
                self.assertEqual(w.main(["generate", str(tmp)]), 0)
                self.assertTrue((tmp / "out" / "b.txt").read_text(encoding="utf-8").endswith("133 1105\n"))
                self.assertEqual(w.main(["check", str(tmp)]), 0)
                (tmp / "out" / "a.txt").write_text("", encoding="utf-8")
                with mock.patch("sys.stderr"):
                    self.assertEqual(w.main(["check", str(tmp)]), 1)

    def test_no_letter_takes_the_line_back(self):
        text = w.table_text(self.measurement({0x0B82: -112, 0x0B83: 1000}), "Measured on Windows")
        self.assertTrue(text.endswith("B82 0\nB83 1000\n"), text)

    def test_a_measured_fraction_of_a_thousandth_rounds_in_the_table(self):
        text = w.table_text(self.measurement({0x6F0: 1143.6, 0x6F1: 1143.4}), "Measured on Windows")
        self.assertTrue(text.endswith("6F0 1144\n6F1 1143\n"), text)

    def test_differences_are_the_code_points_measured_at_another_width(self):
        a = {"widths": {"307": 888, "12354": 2000, "65438": 0}}
        b = {"widths": {"307": 1105, "12354": 2000}}
        self.assertEqual(w.differences(a, b), [(307, 888, 1105)])


class FontsTest(unittest.TestCase):
    """Only the commands that draw need Inconsolata: the others run offline, as in CI."""

    def test_commands_that_only_read_measurements_fetch_no_fonts(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            measured = {"widths": {"307": 888}, "nothing": [], "environment": ["Chromium 1"]}
            for name in ("main.json", "mono.json"):
                (tmp / name).write_text(json.dumps(measured), encoding="utf-8")
            (tmp / "SOURCE.txt").write_text("Measured on Windows\n", encoding="utf-8")
            with mock.patch.object(w, "container_fonts", side_effect=AssertionError("fetched the fonts")), \
                    mock.patch.object(w, "TABLE", tmp / "a.txt"), mock.patch.object(w, "MONO_TABLE", tmp / "b.txt"), \
                    mock.patch.object(w, "REPO", tmp), mock.patch("builtins.print"):
                self.assertEqual(w.main(["diff", str(tmp / "main.json"), str(tmp / "mono.json")]), 0)
                self.assertEqual(w.main(["generate", str(tmp)]), 0)
                self.assertEqual(w.main(["check", str(tmp)]), 0)

    def test_measure_and_trims_draw_with_the_downloaded_fonts_unless_given_others(self):
        for command, function in (("measure", "measure"), ("trims", "trims")):
            with mock.patch.object(w, "default_fonts", return_value=Path("downloaded")), mock.patch.object(w, function) as drawn:
                w.main([command, "out.json"])
                self.assertEqual(drawn.call_args.args[2], Path("downloaded"))
                w.main([command, "out.json", "--fonts", "mine"])
                self.assertEqual(drawn.call_args.args[2], Path("mine"))


class WindowsFontsTest(unittest.TestCase):
    def test_a_missing_font_file_is_said_to_be_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            found = w.windows_fonts(Path(tmp))
        self.assertIn("msgothic.ttc: not on this machine", found)
        self.assertEqual(len(found), len(w.WINDOWS_FONTS))


if __name__ == "__main__":
    unittest.main()
