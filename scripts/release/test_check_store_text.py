"""The store text guard against texts at, over and under Google Play's limits."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest

import check_store_text


class StoreTextTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.folder = self.root / check_store_text.METADATA / "de-DE"
        (self.folder / "changelogs").mkdir(parents=True)

    def write(self, name, length, end="\n"):
        path = self.folder / name
        path.write_text("ü" * length + end, encoding="utf-8")
        return path

    def test_texts_at_their_limits_pass(self):
        self.write("title.txt", 30)
        self.write("short_description.txt", 80)
        self.write("full_description.txt", 4000)
        self.write("changelogs/2100000.txt", 500)
        self.assertEqual(check_store_text.too_long(self.root), [])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(check_store_text.main(self.root), 0)

    def test_a_character_over_fails_counting_characters_not_bytes(self):
        self.write("full_description.txt", 4001, end="")
        self.write("changelogs/2100000.txt", 501)
        self.write("title.txt", 31)
        found = check_store_text.too_long(self.root)
        self.assertEqual(sorted((path.name, length, limit) for path, length, limit in found),
                         [("2100000.txt", 501, 500), ("full_description.txt", 4001, 4000), ("title.txt", 31, 30)])
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_store_text.main(self.root), 1)
        self.assertIn("4001 characters", errors.getvalue())

    def test_other_files_are_left_alone(self):
        self.write("video.txt", 5000)
        self.assertEqual(check_store_text.too_long(self.root), [])

    def test_the_repos_own_texts_pass(self):
        self.assertEqual(check_store_text.too_long(check_store_text.ROOT), [])


if __name__ == "__main__":
    unittest.main()
