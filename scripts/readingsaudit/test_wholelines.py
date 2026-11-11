import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_golden  # noqa: E402
import readingsaudit  # noqa: E402
import wholelines  # noqa: E402

LINE = "夢を見た"
TRUTH = [["夢", "ゆめ"], ["を", None], ["見", "み"], ["た", None]]
RIGHT = [["夢", "ゆめ"], ["を", None], ["見", "み"], ["た", None]]


def song(folder, name, lines):
    """An audit song: each line with UtaTen's ruby, and both dictionaries' parts as [RIGHT]."""
    (folder / "audit").mkdir(exist_ok=True)
    (folder / "audit" / f"{name}.json").write_text(json.dumps({"lines": [
        {"text": text, "truth": truth, "builtIn": RIGHT, "uniDic": RIGHT} for text, truth in lines]}, ensure_ascii=False))


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), "-c", "user.name=test", "-c", "user.email=test@example.invalid", *args],
                          check=True, capture_output=True, text=True).stdout.strip()


def write(repo, path, text):
    (repo / path).parent.mkdir(parents=True, exist_ok=True)
    (repo / path).write_text(text, encoding="utf-8")


def commit(repo, message):
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", message)


class AuditLines(unittest.TestCase):

    def test_each_line_with_its_ruby_once(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            other = [["夢", "む"], ["を", None], ["見", "み"], ["た", None]]
            song(folder, "a", [(LINE, TRUTH), (LINE, TRUTH), ("空", [["空", "そら"]])])
            # The same line in another song is read once, unless UtaTen reads it otherwise there.
            song(folder, "b", [(LINE, TRUTH), ("ruby-less", None)])
            song(folder, "c", [(LINE, other)])
            self.assertEqual([(LINE, TRUTH), ("空", [["空", "そら"]]), (LINE, other)], wholelines.audit_lines(folder))


class ReadWhole(unittest.TestCase):

    def test_the_lines_are_read_by_the_checkout_given_through_the_build_box(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            parts = [["空", "そら"]]

            def songcheck(where, test, root):
                self.assertEqual((folder, wholelines.TEST, Path("/base")), (where, test, root))
                self.assertEqual(["空", "海"], json.loads((where / wholelines.JOB).read_text()))
                (where / wholelines.ANSWER).write_text(json.dumps({"ipadic": [parts, []], "unidic": [[], parts]}))
                return 0
            (folder / wholelines.ANSWER).write_text("{}")
            with mock.patch.object(readingsaudit, "songcheck", side_effect=songcheck):
                read = wholelines.read_whole(folder, Path("/base"), ["空", "海"])
            self.assertEqual({"ipadic": {"空": parts, "海": []}, "unidic": {"空": [], "海": parts}}, read)
            # The songs' lines go once they are answered.
            self.assertEqual([], list(folder.iterdir()))
            # A failed run, or one that wrote nothing, stops the command: an old answer is never read.
            for code in (1, 0):
                with mock.patch.object(readingsaudit, "songcheck", return_value=code), self.assertRaises(SystemExit):
                    wholelines.read_whole(folder, Path("/base"), ["空"])
                self.assertEqual([], list(folder.iterdir()))

    def test_the_songcheck_runs_the_build_box_of_the_checkout_it_reads(self):
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=0) as call:
            readingsaudit.songcheck(Path("/audit"), "com.example.Test", root=Path("/base"))
        self.assertEqual("/base/scripts/gradle-box/gradle-box.sh", call.call_args.args[0][3])
        self.assertEqual(Path("/base"), call.call_args.kwargs["cwd"])


class Checkout(unittest.TestCase):

    def repo(self, folder, harness_at_base):
        repo = Path(folder) / "repo"
        git(Path(folder), "init", "-q", "-b", "main", "repo")
        write(repo, "README.md", "start\n")
        if harness_at_base:
            write(repo, wholelines.HARNESS, "main's\n")
        commit(repo, "test: start")
        base = git(repo, "rev-parse", "HEAD")
        write(repo, wholelines.HARNESS, "this checkout's\n")
        commit(repo, "test: the harness")
        return repo, base

    def test_the_base_is_checked_out_with_this_checkouts_harness_when_it_has_none_and_goes_after(self):
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as near:
            repo, base = self.repo(folder, harness_at_base=False)
            with wholelines.checkout(repo, base, near) as tree:
                self.assertEqual(base, git(tree, "rev-parse", "HEAD"))
                self.assertEqual("this checkout's\n", (tree / wholelines.HARNESS).read_text())
                self.assertEqual(Path(near), tree.parent.parent)
            self.assertEqual([], list(Path(near).iterdir()))
            self.assertNotIn(str(tree), git(repo, "worktree", "list"))

    def test_the_bases_own_harness_stays_and_the_worktree_goes_on_a_failure_too(self):
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as near:
            repo, base = self.repo(folder, harness_at_base=True)
            with self.assertRaises(RuntimeError), wholelines.checkout(repo, base, near) as tree:
                self.assertEqual("main's\n", (tree / wholelines.HARNESS).read_text())
                raise RuntimeError("the build failed")
            self.assertEqual([], list(Path(near).iterdir()))
            self.assertNotIn(str(tree), git(repo, "worktree", "list"))
            # A commit Git can't check out leaves no folder behind either.
            with self.assertRaises(subprocess.CalledProcessError), wholelines.checkout(repo, "0" * 40, near):
                pass
            self.assertEqual([], list(Path(near).iterdir()))


class Changes(unittest.TestCase):

    def read(self, ipadic, unidic=RIGHT):
        return {"ipadic": {LINE: ipadic}, "unidic": {LINE: unidic}}

    def test_a_right_reading_read_with_other_kana_or_split_otherwise_is_a_change(self):
        before = self.read(RIGHT)
        self.assertEqual(([], 4, []), wholelines.changes([(LINE, TRUTH)], before, self.read(RIGHT)))
        other_kana = [["夢", "む"], ["を", None], ["見", "み"], ["た", None]]
        found, right, used = wholelines.changes([(LINE, TRUTH)], before, self.read(other_kana))
        self.assertEqual([("夢", "ゆめ", "ipadic", LINE, "夢=ゆめ", "夢=む")], found)
        self.assertEqual((4, []), (right, used))
        split = [["夢を", "ゆめを"], ["見", "み"], ["た", None]]
        self.assertEqual([("夢", "ゆめ", "unidic", LINE, "夢=ゆめ", "夢を=ゆめを")],
                         wholelines.changes([(LINE, TRUTH)], before, self.read(RIGHT, split))[0])

    def test_a_reading_wrong_before_may_change_and_an_accepted_one_is_named(self):
        wrong = [["夢", "む"], ["を", None], ["見", "み"], ["た", None]]
        self.assertEqual(([], 3, []), wholelines.changes([(LINE, TRUTH)], self.read(wrong), self.read(RIGHT)))
        found, right, used = wholelines.changes([(LINE, TRUTH)], self.read(RIGHT), self.read(wrong), {("夢", "ゆめ")})
        self.assertEqual(([], 4, [("夢", "ゆめ")]), (found, right, used))


class Command(unittest.TestCase):

    def repo(self, folder):
        """A repository with a readings source on main, and a branch that changes it."""
        repo = Path(folder) / "repo"
        git(Path(folder), "init", "-q", "-b", "main", "repo")
        write(repo, wholelines.golden.READING_SOURCES[0], "one\n")
        write(repo, wholelines.HARNESS, "harness\n")
        commit(repo, "test: start")
        git(repo, "checkout", "-q", "-b", "pr")
        write(repo, wholelines.golden.READING_SOURCES[0], "two\n")
        commit(repo, "test: change a reading")
        return repo

    def run_lines(self, repo, after, accepted=()):
        """The command with a reader that answers [RIGHT] in main's checkout and [after] in this one."""
        audit_folder = repo.parent / "audit-data"
        audit_folder.mkdir(exist_ok=True)
        song(audit_folder, "a", [(LINE, TRUTH)])
        roots = []

        def read(folder, root, texts):
            self.assertEqual((audit_folder, [LINE]), (folder, texts))
            roots.append(root)
            parts = RIGHT if root != repo else after
            return {"ipadic": {LINE: parts}, "unidic": {LINE: RIGHT}}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = wholelines.lines(audit_folder, accepted, base="main", repo=repo, read=read)
        # main's checkout is read first, in a worktree next to the audit folder, gone after.
        self.assertEqual(2, len(roots))
        self.assertEqual(audit_folder.parent, roots[0].parent.parent)
        self.assertEqual(repo, roots[1])
        self.assertFalse(roots[0].exists())
        return code, out.getvalue()

    def test_no_change_prints_the_line_for_the_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            code, text = self.run_lines(repo, RIGHT)
            self.assertEqual(0, code, text)
            self.assertIn("4 right readings on 1 whole lines", text)
            digest = check_golden.readings_digest(repo, git(repo, "rev-parse", "main"), "HEAD")
            self.assertTrue(text.endswith(f"\ngolden: lines checked {digest}\n"), text)
            self.assertNotIn("golden: accept", text)

    def test_a_change_fails_until_accepted_and_the_accept_goes_in_the_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            other = [["夢", "む"], ["を", None], ["見", "み"], ["た", None]]
            code, text = self.run_lines(repo, other)
            self.assertEqual(1, code)
            self.assertIn("  夢 ゆめ ipadic: 夢=ゆめ -> 夢=む  in 夢を見た\n", text)
            self.assertNotIn("golden: lines checked", text)
            code, text = self.run_lines(repo, other, [["夢", "ゆめ"]])
            self.assertEqual(0, code, text)
            self.assertIn("\ngolden: accept 夢 ゆめ\ngolden: lines checked ", text)

    def test_an_uncommitted_readings_change_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            write(repo, wholelines.golden.READING_SOURCES[0], "three\n")
            with self.assertRaises(SystemExit) as stop:
                wholelines.lines(repo.parent, [], base="main", repo=repo, read=None)
            self.assertIn("Commit the readings change first", str(stop.exception))

    def test_the_command_runs_it_on_the_data_folder(self):
        argv = ["readingsaudit.py", "lines", "--base", "origin/x", "--accept", "夢", "ゆめ"]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(wholelines, "lines", return_value=1) as lines, \
                mock.patch.object(readingsaudit, "data_dir", return_value=Path("/audit")), self.assertRaises(SystemExit) as stop:
            readingsaudit.main()
        self.assertEqual(1, stop.exception.code)
        self.assertEqual((Path("/audit"), [["夢", "ゆめ"]], "origin/x"), lines.call_args.args)


if __name__ == "__main__":
    unittest.main()
