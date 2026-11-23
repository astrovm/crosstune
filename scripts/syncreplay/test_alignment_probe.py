import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('alignment_probe', Path(__file__).with_name('alignment-probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class AlignmentInputTest(unittest.TestCase):
    def test_replay_uses_nearest_window_and_excludes_words_before_start(self):
        found = {'lines': [[1000, 'hello', [[1100, 'hello']], 2000], [3000, 'world', []]]}
        heard = [[1000, 'too early'], [8000, ' hello '], [9000, '']]
        self.assertEqual([
            'N\t7000', 'L\t1000\taGVsbG8=\t2000', 'W\t0\t1100\taGVsbG8=',
            'L\t3000\td29ybGQ=\t', 'H\t1000\taGVsbG8=',
        ], probe.input_rows(found, heard, 7000, 0))


if __name__ == '__main__':
    unittest.main()
