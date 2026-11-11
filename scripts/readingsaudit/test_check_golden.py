import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_golden  # noqa: E402
from readingsaudit import GOLDEN_REL, READING_SOURCES  # noqa: E402

READINGS = READING_SOURCES[0]
FURIGANA = READING_SOURCES[1]


def git(repo, *args):
    subprocess.run(["git", "-C", str(repo), "-c", "user.name=test", "-c", "user.email=test@example.invalid", *args],
                   check=True, capture_output=True, text=True)


def commit(repo, message):
    git(repo, "add", "-A")
    git(repo, "commit", "-m", message)


class Verdict(unittest.TestCase):

    def test_other_files_pass(self):
        self.assertIsNone(check_golden.verdict(["README.md"], []))

    def test_readings_alone_fails_and_the_golden_file_or_the_mark_passes(self):
        self.assertIsNotNone(check_golden.verdict([READINGS], ["fix a reading"]))
        self.assertIsNotNone(check_golden.verdict([FURIGANA], ["fix a reading"]))
        self.assertIsNone(check_golden.verdict([READINGS, GOLDEN_REL], ["fix a reading"]))
        self.assertIsNone(check_golden.verdict([FURIGANA], ["refactor\n\ngolden: none"]))
        # The mark has to be its own words. A lookalike does not count.
        self.assertIsNotNone(check_golden.verdict([READINGS], ["golden: nope"]))


class FakeDiff(unittest.TestCase):

    def run_check(self, repo):
        # The lint job checks the merge commit, so the range is the whole pull request.
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = check_golden.main(["--repo", str(repo), "--base", "HEAD^1", "--head", "HEAD"])
        return code, buf.getvalue()

    def merge(self, repo):
        """A merge commit, as the lint job has for a pull request: main, then this branch."""
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--no-ff", "-q", "-m", "test: merge the change", "pr")

    def test_a_readings_change_alone_fails_until_the_golden_file_or_the_mark(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            git(repo, "init", "-b", "main")
            (repo / "README.md").write_text("start\n")
            commit(repo, "test: start")
            git(repo, "checkout", "-q", "-b", "pr")
            readings = repo / READINGS
            readings.parent.mkdir(parents=True)
            readings.write_text("one\n")
            commit(repo, "test: change a reading")
            self.merge(repo)
            code, text = self.run_check(repo)
            self.assertEqual(1, code)
            self.assertIn("does not change the golden readings file", text)

    def test_the_golden_file_in_the_same_pull_request_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            git(repo, "init", "-b", "main")
            (repo / "README.md").write_text("start\n")
            commit(repo, "test: start")
            git(repo, "checkout", "-q", "-b", "pr")
            readings = repo / READINGS
            readings.parent.mkdir(parents=True)
            readings.write_text("one\n")
            commit(repo, "test: change a reading")
            golden = repo / GOLDEN_REL
            golden.parent.mkdir(parents=True)
            golden.write_text("row\n")
            commit(repo, "test: lock the reading")
            self.merge(repo)
            self.assertEqual((0, ""), self.run_check(repo))

    def test_golden_none_in_a_commit_message_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            git(repo, "init", "-b", "main")
            (repo / "README.md").write_text("start\n")
            commit(repo, "test: start")
            git(repo, "checkout", "-q", "-b", "pr")
            readings = repo / READINGS
            readings.parent.mkdir(parents=True)
            readings.write_text("one\n")
            commit(repo, "test: refactor the reader\n\ngolden: none")
            self.merge(repo)
            self.assertEqual((0, ""), self.run_check(repo))

    def test_a_mark_in_an_older_commit_survives_the_lint_jobs_two_commit_fetch(self):
        # fetch-depth 2 keeps the merge and the latest commit. The mark is on the commit before that.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            repo = root / "src"
            repo.mkdir()
            git(repo, "init", "-b", "main")
            (repo / "README.md").write_text("start\n")
            commit(repo, "test: start")
            git(repo, "checkout", "-q", "-b", "pr")
            readings = repo / READINGS
            readings.parent.mkdir(parents=True)
            readings.write_text("one\n")
            commit(repo, "test: refactor the reader\n\ngolden: none")
            readings.write_text("two\n")
            commit(repo, "test: touch a comment")
            self.merge(repo)
            bare = root / "bare.git"
            shallow = root / "shallow"
            git(repo, "clone", "-q", "--bare", str(repo), str(bare))
            git(repo, "clone", "-q", "--depth", "2", "--branch", "main", f"file://{bare}", str(shallow))
            code, text = self.run_check(shallow)
            self.assertEqual(0, code, text)


if __name__ == "__main__":
    unittest.main()
