import sys
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
