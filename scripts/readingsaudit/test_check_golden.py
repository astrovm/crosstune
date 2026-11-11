import contextlib
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_golden  # noqa: E402
from golden import GOLDEN_REL, READING_BUILD, READING_SOURCES  # noqa: E402

HERE = Path(__file__).resolve().parent

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
        self.assertEqual((True, set(), False), check_golden.marks("Refactor.\n\n  golden: none  \n"))
        self.assertEqual((True, set(), False), check_golden.marks("golden: none"))
        self.assertEqual((False, set(), True), check_golden.marks("Moved.\ngolden: lock changed\n"))
        # A lookalike, the phrase inside a sentence or a list item, and a quote of it do not count.
        for body in ("golden: nonexistent", "Put golden: none in the body.", "- golden: none", "`golden: none`", "",
                     "golden: lock changed, sorry", "- golden: lock changed"):
            self.assertEqual((False, set(), False), check_golden.marks(body), body)

    def test_an_accept_names_a_word_and_its_kana(self):
        body = "golden: accept 甲乙 こうおつ\ngolden: accept 丙\ngolden: accept 丙 へい てい\nsee golden: accept 丁 てい"
        self.assertEqual((False, {("甲乙", "こうおつ")}, False), check_golden.marks(body))


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

    def test_a_change_to_the_lock_needs_its_mark(self):
        problems = check_golden.verdict(False, False, set(), "", {"b.kt", "a.py"})
        self.assertIn("changes the golden readings lock itself:\n  a.py\n  b.kt\n", problems[0])
        self.assertEqual([], check_golden.verdict(False, False, set(), "golden: lock changed", {"a.py"}))


class Removed(unittest.TestCase):

    def test_a_row_that_another_dictionary_now_reads_so_too_is_kept(self):
        ipadic, both, unidic = (ROW[:5] + (tag,) for tag in ("ipadic", "both", "unidic"))
        self.assertEqual(set(), check_golden.removed_rows({ipadic}, {both}))
        self.assertEqual(set(), check_golden.removed_rows({both}, {ipadic, unidic}))
        self.assertEqual(set(), check_golden.removed_rows({ipadic}, {ipadic}))
        # Narrower, or the other dictionary only, drops the lock.
        self.assertEqual({both}, check_golden.removed_rows({both}, {ipadic}))
        self.assertEqual({ipadic}, check_golden.removed_rows({ipadic}, {unidic}))
        # Any other field changed is another row.
        self.assertEqual({ipadic}, check_golden.removed_rows({ipadic}, {("甲乙", "甲乙", "", "こういつ", "甲乙=こういつ", "both")}))
        self.assertEqual({("甲乙",)}, check_golden.removed_rows({("甲乙",)}, {both, ("甲乙", "甲乙")}))


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

    def test_a_row_another_dictionary_now_reads_so_too_passes_and_one_it_no_longer_does_fails(self):
        ipadic, both = ROW[:5] + ("ipadic",), ROW[:5] + ("both",)
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(ipadic))
            self.write(repo, GOLDEN_REL, golden_text(both))
            self.merge(repo, "test: unidic reads it right too")
            self.assertEqual((0, ""), self.run_check(repo))
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(both))
            self.write(repo, GOLDEN_REL, golden_text(ipadic))
            self.merge(repo, "test: unidic no longer reads it right")
            code, text = self.run_check(repo)
            self.assertEqual(1, code)
            self.assertIn("甲乙 こうおつ both 甲乙", text)

    def test_a_readings_change_with_only_a_comment_or_the_order_of_the_file_changed_fails(self):
        for after in ("# Edited.\n" + golden_text(OTHER, ROW), golden_text(ROW, OTHER) + "\n"):
            with tempfile.TemporaryDirectory() as folder:
                repo = self.repo(folder, golden_text(OTHER, ROW))
                self.write(repo, READINGS, "one\n")
                self.write(repo, GOLDEN_REL, after)
                self.merge(repo, "test: change a reading and touch the file")
                code, text = self.run_check(repo)
                self.assertEqual(1, code, after)
                self.assertIn("does not change the golden readings file", text)

    def test_a_change_to_the_lock_itself_fails_until_the_body_says_so(self):
        for path in check_golden.LOCK_FILES:
            with tempfile.TemporaryDirectory() as folder:
                repo = self.repo(folder)
                self.write(repo, path, "one\n")
                self.merge(repo, "test: change the lock")
                code, text = self.run_check(repo)
                self.assertEqual(1, code, path)
                self.assertIn(f"  {path}\n", text)
                self.assertEqual((0, ""), self.run_check(repo, "Why.\ngolden: lock changed\n"))

    def test_moving_the_golden_file_fails_until_the_body_says_so_and_its_rows_still_count(self):
        moved = "app/src/test/resources/elsewhere.tsv"
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(OTHER, ROW))
            git(repo, "mv", GOLDEN_REL, moved)
            self.merge(repo, "test: move the file")
            code, text = self.run_check(repo)
            self.assertEqual(1, code)
            self.assertIn(f"  {GOLDEN_REL}\n", text)
            self.assertEqual((0, ""), self.run_check(repo, "golden: lock changed"))
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(OTHER, ROW))
            git(repo, "mv", GOLDEN_REL, moved)
            self.write(repo, moved, golden_text(OTHER, ROW).replace("# Locked readings.", "# Locked readings, moved."))
            self.merge(repo, "test: move the file")
            self.assertEqual((0, ""), self.run_check(repo, "golden: lock changed"))
        # Deleted, every row goes: the lock mark does not let them.
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, golden_text(ROW))
            git(repo, "rm", "-q", GOLDEN_REL)
            self.merge(repo, "test: drop the file")
            code, text = self.run_check(repo, "golden: lock changed")
            self.assertEqual(1, code)
            self.assertIn("removes or changes 1 locked readings", text)


class BaseChecker(unittest.TestCase):
    """The checker main has runs, not the one the pull request brings."""

    def repo(self, folder, rows):
        """A repository whose main has this checker, the golden code and the golden file, and the branch pr checked out."""
        repo = Path(folder)
        git(repo, "init", "-b", "main")
        for name in ("check_golden.py", "golden.py", "readingsaudit.py"):
            (repo / check_golden.SCRIPTS).mkdir(parents=True, exist_ok=True)
            shutil.copy(HERE / name, repo / check_golden.SCRIPTS / name)
        (repo / GOLDEN_REL).parent.mkdir(parents=True)
        (repo / GOLDEN_REL).write_text(golden_text(*rows), encoding="utf-8")
        commit(repo, "test: start")
        git(repo, "checkout", "-q", "-b", "pr")
        return repo

    def run_check(self, repo, body):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "body.md").write_text(body, encoding="utf-8")
            return subprocess.run([sys.executable, str(repo / check_golden.CHECKER), "--repo", str(repo), "--body-file", "body.md"],
                                  cwd=folder, capture_output=True, text=True)

    def merge(self, repo, message):
        commit(repo, message)
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--no-ff", "-q", "-m", "test: merge the change", "pr")

    def test_a_pull_request_that_moves_the_file_drops_a_row_and_points_the_code_at_it_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, [ROW, OTHER])
            moved = "app/src/test/resources/elsewhere.tsv"
            git(repo, "mv", GOLDEN_REL, moved)
            (repo / moved).write_text(golden_text(OTHER), encoding="utf-8")
            code = repo / check_golden.SCRIPTS / "golden.py"
            code.write_text(code.read_text(encoding="utf-8").replace(GOLDEN_REL, moved), encoding="utf-8")
            self.merge(repo, "test: move the lock and drop a row")
            done = self.run_check(repo, "golden: lock changed\n")
            self.assertEqual(1, done.returncode, done.stdout + done.stderr)
            self.assertIn("甲乙 こうおつ both 甲乙", done.stdout)

    def test_a_pull_request_that_makes_the_checker_pass_everything_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, [ROW])
            checker = repo / check_golden.CHECKER
            checker.write_text(checker.read_text(encoding="utf-8").replace("return 1 if problems else 0", "return 0"), encoding="utf-8")
            (repo / GOLDEN_REL).write_text(golden_text(), encoding="utf-8")
            self.merge(repo, "test: loosen the checker")
            done = self.run_check(repo, "")
            self.assertEqual(1, done.returncode, done.stdout + done.stderr)
            self.assertIn(f"  {check_golden.CHECKER}\n", done.stdout)
            self.assertIn("removes or changes 1 locked readings", done.stdout)
            # With its own checker it would pass: that is what running main's prevents.
            own = subprocess.run([sys.executable, str(checker), "--own", "--repo", str(repo)], capture_output=True, text=True)
            self.assertEqual(0, own.returncode, own.stdout + own.stderr)

    def test_the_base_checker_reads_the_same_range_and_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder, [ROW])
            (repo / GOLDEN_REL).write_text(golden_text(), encoding="utf-8")
            self.merge(repo, "test: drop a lock on purpose")
            with tempfile.TemporaryDirectory() as other:
                body = Path(other) / "body.md"
                body.write_text("golden: accept 甲乙 こうおつ\n", encoding="utf-8")
                self.assertEqual(0, check_golden.main(["--repo", str(repo), "--body-file", str(body)]))
                self.assertEqual(1, check_golden.main(["--repo", str(repo)]))


if __name__ == "__main__":
    unittest.main()
