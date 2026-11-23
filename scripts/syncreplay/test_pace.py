import unittest

from scripts.syncreplay.pace import adjacent_paces


class PaceTest(unittest.TestCase):
    def test_adjacent_unspaced_lines_use_the_first_lines_written_letters(self):
        lines = [
            {'line': 0, 'text': '夜に駆け', 'start': 1.0},
            {'line': 1, 'text': '한 글', 'start': 1.8},
            {'line': 3, 'text': '別れ', 'start': 2.0},
            {'line': 4, 'text': '終わり', 'start': 2.4},
        ]
        paces = adjacent_paces(lines)
        self.assertEqual([0, 3], [line for line, _ in paces])
        self.assertAlmostEqual(200, paces[0][1])
        self.assertAlmostEqual(200, paces[1][1])

    def test_latin_lines_and_nonchronological_pairs_are_not_counted(self):
        lines = [
            {'line': 0, 'text': 'hello', 'start': 1.0},
            {'line': 1, 'text': '밤', 'start': 2.0},
            {'line': 2, 'text': '하늘', 'start': 1.5},
        ]
        self.assertEqual([], adjacent_paces(lines))


if __name__ == '__main__':
    unittest.main()
