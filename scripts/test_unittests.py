"""unittests.py on made-up folders of tests, one that passes and one that fails."""
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unittests


class UnittestsTest(unittest.TestCase):
    def test_every_folder_with_a_test_is_run(self):
        found = unittests.folders()
        tests = {path.parent.relative_to(unittests.ROOT) for path in (unittests.ROOT / "scripts").rglob("test_*.py")}
        self.assertEqual(sorted(tests), found)
        self.assertIn(Path("scripts"), found)
        self.assertIn(Path("scripts/lib"), found)

    def test_every_folder_runs_and_the_failed_ones_are_named(self):
        with tempfile.TemporaryDirectory() as temporary:
            passing, failing = Path(temporary, "passing"), Path(temporary, "failing")
            passing.mkdir()
            failing.mkdir()
            # Each folder's own module of the same name, which a shared process would mix up.
            (passing / "same.py").write_text("ANSWER = 1\n")
            (failing / "same.py").write_text("ANSWER = 2\n")
            test = "import unittest\nimport same\n\nclass T(unittest.TestCase):\n    def test(self):\n        self.assertEqual(1, same.ANSWER)\n"
            (passing / "test_one.py").write_text(test)
            (failing / "test_one.py").write_text(test)
            errors = io.StringIO()
            with redirect_stderr(errors):
                self.assertEqual(1, unittests.main([str(failing), str(passing)]))
                self.assertEqual(0, unittests.main([str(passing)]))
        self.assertIn(f"Failed: {failing}", errors.getvalue())
        self.assertNotIn(str(passing), errors.getvalue().split("Failed:")[-1])


if __name__ == "__main__":
    unittest.main()
