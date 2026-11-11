import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_golden  # noqa: E402
from readingsaudit import GOLDEN_REL, READING_BUILD, READING_SOURCES  # noqa: E402

READINGS = READING_SOURCES[0]
ROW = ("甲乙", "甲乙", "", "こうおつ", "甲乙=こうおつ", "both")
OTHER = ("丙", "丙", "", "へい", "丙=へい", "both")


def git(repo, *args):
    subprocess.run(["git", "-C", str(repo), "-c", "user.name=test", "-c", "user.email=test@example.invalid", *args],
                   check=True, capture_output=True, text=True)


def commit(repo, message):
    git(repo, "add", "-A")
    git(repo, "commit", "-m", message)


def golden_text(*rows):
    return "# Locked readings.\n" + f"# rows: {len(rows)}\n" + "".join("\t".join(row) + "\n" for row in rows)


class Marks(unittest.TestCase):

    def test_a_mark_is_a_whole_line_of_the_body(self):
        self.assertEqual((True, set()), check_golden.marks("Refactor.\n\n  golden: none  \n"))
        self.assertEqual((True, set()), check_golden.marks("golden: none"))
        # A lookalike, the phrase inside a sentence or a list item, and a quote of it do not count.
        for body in ("golden: nonexistent", "Put golden: none in the body.", "- golden: none", "`golden: none`", ""):
            self.assertEqual((False, set()), check_golden.marks(body), body)

    def test_an_accept_names_a_word_and_its_kana(self):
        body = "golden: accept 甲乙 こうおつ\ngolden: accept 丙\ngolden: accept 丙 へい てい\nsee golden: accept 丁 てい"
        self.assertEqual((False, {("甲乙", "こうおつ")}), check_golden.marks(body))


class Verdict(unittest.TestCase):

    def test_nothing_to_say_on_other_changes(self):
        self.assertEqual([], check_golden.verdict(False, False, set(), ""))

    def test_a_readings_change_needs_the_golden_file_or_the_mark(self):
        self.assertIn("does not change the golden readings file", check_golden.verdict(True, False, set(), "")[0])
        self.assertEqual([], check_golden.verdict(True, True, set(), ""))
        self.assertEqual([], check_golden.verdict(True, False, set(), "golden: none"))

    def test_a_removed_row_needs_an_accept_for_its_word_and_kana(self):
        problems = check_golden.verdict(False, True, {ROW}, "")
        self.assertIn("removes or changes 1 locked readings", problems[0])
        self.assertIn("甲乙 こうおつ both 甲乙", problems[0])
        self.assertEqual([], check_golden.verdict(False, True, {ROW}, "golden: accept 甲乙 こうおつ"))
        # golden: none is for code that changes no reading. Another kana of the word accepts nothing.
        self.assertTrue(check_golden.verdict(False, True, {ROW}, "golden: none"))
        self.assertTrue(check_golden.verdict(False, True, {ROW}, "golden: accept 甲乙 こういつ"))

    def test_a_row_cut_short_is_named_too(self):
        self.assertIn("甲乙", check_golden.verdict(False, True, {("甲乙",)}, "golden: accept 甲乙 こうおつ")[0])


class FakeDiff(unittest.TestCase):
    """The lint job's range on a fake repository: the merge commit, and main before it."""

    def run_check(self, repo, body=None):
        args = ["--repo", str(repo), "--base", "HEAD^1", "--head", "HEAD"]
        buf = io.StringIO()
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(buf):
            if body is not None:
                (Path(folder) / "body.md").write_text(body, encoding="utf-8")
                args += ["--body-file", str(Path(folder) / "body.md")]
            code = check_golden.main(args)
        return code, buf.getvalue()

    def repo(self, folder, golden=None):
        """A repository with main, golden rows on it when given, and the branch pr checked out."""
        repo = Path(folder)
        git(repo, "init", "-b", "main")
        (repo / "README.md").write_text("start\n")
        (repo / READING_BUILD).parent.mkdir(parents=True)
        (repo / READING_BUILD).write_text('implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")\nimplementation("other:lib:1.0")\n')
        if golden is not None:
            self.write(repo, GOLDEN_REL, golden)
        commit(repo, "test: start")
        git(repo, "checkout", "-q", "-b", "pr")
        return repo

    def write(self, repo, path, text):
        (repo / path).parent.mkdir(parents=True, exist_ok=True)
        (repo / path).write_text(text, encoding="utf-8")

    def merge(self, repo, message):
        """A commit on the branch, then the merge commit the lint job checks out: main, then the branch."""
        commit(repo, message)
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--no-ff", "-q", "-m", "test: merge the change", "pr")

    def test_a_readings_change_alone_fails_until_the_golden_file_or_the_mark(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.write(repo, READINGS, "one\n")
            # A mark in a commit message is not read: the body is the one place.
            self.merge(repo, "test: change a reading\n\ngolden: none")
            code, text = self.run_check(repo)
            self.assertEqual(1, code)
            self.assertIn("does not change the golden readings file", text)
            self.assertEqual((0, ""), self.run_check(repo, "Refactor.\n\ngolden: none\n"))

    def test_each_reading_source_and_the_dictionaries_version_count(self):
        for path in READING_SOURCES[1:]:
            with tempfile.TemporaryDirectory() as folder:
                repo = self.repo(folder)
                self.write(repo, path, "one\n")
                self.merge(repo, "test: change a reading")
                self.assertEqual(1, self.run_check(repo)[0], path)
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.write(repo, READING_BUILD, 'implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.1")\nimplementation("other:lib:1.0")\n')
            self.merge(repo, "build: bump kuromoji")
            self.assertEqual(1, self.run_check(repo)[0])
        # Another library in the same file is no readings change.
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.write(repo, READING_BUILD, 'implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")\nimplementation("other:lib:2.0")\n')
            self.merge(repo, "build: bump other")
            self.assertEqual((0, ""), self.run_check(repo))

    def test_the_golden_file_in_the_same_pull_request_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.write(repo, READINGS, "one\n")
            self.write(repo, GOLDEN_REL, golden_text(ROW))
            self.merge(repo, "test: change a reading and lock it")
            self.assertEqual((0, ""), self.run_check(repo))

    def test_adding_rows_passes_and_removing_or_changing_one_fails_until_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(ROW))
            self.write(repo, GOLDEN_REL, golden_text(OTHER, ROW))
            self.merge(repo, "test: lock another reading")
            self.assertEqual((0, ""), self.run_check(repo))
        changed = ("甲乙", "甲乙", "", "こういつ", "甲乙=こういつ", "both")
        for after in (golden_text(), golden_text(OTHER), golden_text(changed)):
            with tempfile.TemporaryDirectory() as folder:
                repo = self.repo(folder, golden_text(ROW))
                self.write(repo, GOLDEN_REL, after)
                self.merge(repo, "test: drop a lock")
                code, text = self.run_check(repo)
                self.assertEqual(1, code, after)
                self.assertIn("甲乙 こうおつ both 甲乙", text)
                self.assertEqual((0, ""), self.run_check(repo, "Sung otherwise.\ngolden: accept 甲乙 こうおつ\n"))

    def test_deleting_the_file_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(ROW))
            (repo / GOLDEN_REL).unlink()
            self.merge(repo, "test: drop the file")
            self.assertEqual(1, self.run_check(repo)[0])


if __name__ == "__main__":
    unittest.main()
