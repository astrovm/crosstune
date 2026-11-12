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
import golden  # noqa: E402
import readingsaudit  # noqa: E402
import wholelines  # noqa: E402

LINE = "夢を見た"
TRUTH = [["夢", "ゆめ"], ["を", None], ["見", "み"], ["た", None]]
RIGHT = [["夢", "ゆめ"], ["を", None], ["見", "み"], ["た", None]]
WRONG = [["夢", "む"], ["を", None], ["見", "み"], ["た", None]]
WORDS = [["夢", "ゆめ", "yume", "yume"], ["を", None, "o", "wo"], ["見た", "みた", "mita", "mita"]]


def song(folder, name, lines):
    """An audit song: each line with UtaTen's ruby, and both dictionaries' parts as [RIGHT]."""
    (folder / "audit").mkdir(exist_ok=True)
    (folder / "audit" / f"{name}.json").write_text(json.dumps({"lines": [
        {"text": text, "truth": truth, "builtIn": RIGHT, "uniDic": RIGHT} for text, truth in lines]}, ensure_ascii=False))


def read(parts=RIGHT, romaji=("yume o mita", "yume wo mita"), shown=None, words=WORDS, shown_words=None):
    """One line as WholeLines answers it."""
    return {"parts": parts, "romaji": list(romaji), "shown": shown or parts, "shownRomaji": list(romaji),
            "words": words, "shownWords": shown_words or words}


def answer(ipadic, unidic=None, scripts=("JAPANESE",)):
    return {"ipadic": [ipadic], "unidic": [unidic or read()], "scripts": list(scripts)}


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
            # Each song's lines, all of them, for the script the app takes it for.
            self.assertEqual([[LINE, LINE, "空"], [LINE, "ruby-less"], [LINE]], wholelines.audit_songs(folder))

    def test_the_hash_names_the_lines_and_the_songs(self):
        entries, songs = [(LINE, TRUTH)], [[LINE]]
        digest = wholelines.audit_hash(entries, songs)
        self.assertRegex(digest, "^[0-9a-f]{16}$")
        self.assertEqual(digest, wholelines.audit_hash([(LINE, TRUTH)], [[LINE]]))
        self.assertNotEqual(digest, wholelines.audit_hash([], songs))
        self.assertNotEqual(digest, wholelines.audit_hash(entries, [[LINE, "空"]]))

    def test_the_audit_line_is_read_from_the_golden_files_header(self):
        line = "# audit: 3 lines, 14 right readings, sha256 0123456789abcdef"
        self.assertEqual((3, 14, "0123456789abcdef"), wholelines.audit_line(f"# Locked.\n{line}\r\n# rows: 1\nrow\n"))
        self.assertIsNone(wholelines.audit_line("# Locked.\n# rows: 1\n"))
        self.assertIsNone(wholelines.audit_line("# audit: 3 lines, sha256 0123456789abcdef\n"))
        self.assertIsNone(wholelines.audit_line(""))


class ReadWhole(unittest.TestCase):

    def test_both_checkouts_read_the_job_in_one_build_box_run(self):
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as trees:
            folder, base, head = Path(folder), Path(trees) / "base", Path(trees) / "head"
            base.mkdir()
            head.mkdir()
            entries, songs = [(LINE, TRUTH)], [[LINE]]

            def songcheck(where, test, root, also):
                self.assertEqual((folder, wholelines.TEST, head, [base]), (where, test, root, also))
                job = json.loads((where / wholelines.JOB).read_text())
                self.assertEqual([{"text": LINE, "truth": TRUTH}], job["lines"])
                self.assertEqual(songs, job["songs"])
                self.assertEqual({str(base.resolve()): "lines-answer-0.json", str(head.resolve()): "lines-answer-1.json"}, job["answers"])
                for at, name in enumerate(("lines-answer-0.json", "lines-answer-1.json")):
                    (where / name).write_text(json.dumps({"at": at}))
                return 0
            # An old answer is gone before the run.
            (folder / "lines-answer-1.json").write_text("{}")
            with mock.patch.object(readingsaudit, "songcheck", side_effect=songcheck):
                self.assertEqual([{"at": 0}, {"at": 1}], wholelines.read_whole(folder, [base, head], entries, songs))
            # The songs' lines go once they are answered.
            self.assertEqual([], list(folder.iterdir()))
            # A failed run, or one that wrote nothing, stops the command: an old answer is never read.
            for code in (1, 0):
                with mock.patch.object(readingsaudit, "songcheck", return_value=code), self.assertRaises(SystemExit):
                    wholelines.read_whole(folder, [base, head], entries, songs)
                self.assertEqual([], list(folder.iterdir()))

    def test_the_songcheck_runs_the_build_box_of_the_checkout_it_reads_and_the_others_in_it(self):
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=0) as call:
            readingsaudit.songcheck(Path("/audit"), "com.example.Test", root=Path("/head"))
        self.assertEqual(["nice", "-n", "10", "/head/scripts/gradle-box/gradle-box.sh", "--max-workers=2", ":app:songCheck"],
                         call.call_args.args[0][:6])
        self.assertEqual(Path("/head"), call.call_args.kwargs["cwd"])
        with mock.patch.object(readingsaudit.subprocess, "call", return_value=0) as call:
            readingsaudit.songcheck(Path("/audit"), "com.example.Test", root=Path("/head"), also=[Path("/base")])
        self.assertEqual(["/head/scripts/gradle-box/gradle-box.sh", "--also=/base", "--max-workers=2"], call.call_args.args[0][3:6])
        # Only gradle-box runs more than one checkout.
        with self.assertRaises(SystemExit):
            readingsaudit.songcheck(Path("/audit"), "com.example.Test", gradle="./gradlew", also=[Path("/base")])


class Checkout(unittest.TestCase):

    def test_the_base_is_checked_out_with_this_checkouts_harness_and_goes_after(self):
        for harness_at_base in (False, True):
            with self.subTest(harness_at_base=harness_at_base), tempfile.TemporaryDirectory() as folder, \
                    tempfile.TemporaryDirectory() as near:
                repo = Path(folder) / "repo"
                git(Path(folder), "init", "-q", "-b", "main", "repo")
                write(repo, "README.md", "start\n")
                if harness_at_base:
                    write(repo, wholelines.HARNESS, "main's\n")
                commit(repo, "test: start")
                base = git(repo, "rev-parse", "HEAD")
                write(repo, wholelines.HARNESS, "this checkout's\n")
                commit(repo, "test: the harness")
                with wholelines.checkout(repo, base, near) as tree:
                    self.assertEqual(base, git(tree, "rev-parse", "HEAD"))
                    # main's own harness too gives way: both read with the same one.
                    self.assertEqual("this checkout's\n", (tree / wholelines.HARNESS).read_text())
                    self.assertEqual(Path(near), tree.parent.parent)
                self.assertEqual([], list(Path(near).iterdir()))
                self.assertNotIn(str(tree), git(repo, "worktree", "list"))
                with self.assertRaises(RuntimeError), wholelines.checkout(repo, base, near):
                    raise RuntimeError("the build failed")
                self.assertEqual([], list(Path(near).iterdir()))
                # A commit Git can't check out leaves no folder behind either.
                with self.assertRaises(subprocess.CalledProcessError), wholelines.checkout(repo, "0" * 40, near):
                    pass
                self.assertEqual([], list(Path(near).iterdir()))


class Changes(unittest.TestCase):

    def changes(self, before, after, accepted=(), accept_romaji=False, songs=((LINE,),)):
        return wholelines.changes([(LINE, TRUTH)], [list(lines) for lines in songs], before, after, accepted, accept_romaji)

    def test_nothing_changed_names_what_main_reads_right(self):
        found, right, used, romaji = self.changes(answer(read()), answer(read()))
        self.assertEqual(([], [], False), (found, used, romaji))
        # 夢 and 見 with each dictionary; the words 夢 and 見た.
        self.assertEqual({"ruby": 4, "shown": 4, "words": 4, "shown words": 4}, right)

    def test_a_right_reading_read_with_other_kana_or_split_otherwise_is_a_change(self):
        found = self.changes(answer(read()), answer(read(WRONG, words=WORDS)))[0]
        self.assertEqual([("ruby", "夢", "ゆめ", "ipadic", LINE, "夢=ゆめ", "夢=む"), ("shown", "夢", "ゆめ", "ipadic", LINE, "夢=ゆめ", "夢=む")], found)
        split = [["夢を", "ゆめを"], ["見", "み"], ["た", None]]
        self.assertIn(("ruby", "夢", "ゆめ", "unidic", LINE, "夢=ゆめ", "夢を=ゆめを"),
                      self.changes(answer(read()), answer(read(), read(split, words=WORDS)))[0])

    def test_a_reading_put_in_from_utaten_that_goes_is_a_change(self):
        # main shows UtaTen's ゆめ over its own む; this checkout shows its own.
        found = self.changes(answer(read(WRONG, shown=RIGHT)), answer(read(WRONG)))[0]
        self.assertEqual([("shown", "夢", "ゆめ", "ipadic", LINE, "夢=ゆめ", "夢=む")], found)

    def test_a_reading_wrong_before_may_change_and_an_accepted_one_is_named(self):
        wrong_words = [["夢", "む", "mu", "mu"], *WORDS[1:]]
        found, right, used, _romaji = self.changes(answer(read(WRONG, words=wrong_words)), answer(read()))
        self.assertEqual(([], 3, 3), (found, right["ruby"], right["words"]))
        found, _right, used, _romaji = self.changes(answer(read()), answer(read(WRONG, words=wrong_words)), {("夢", "ゆめ")})
        self.assertEqual(([], [("夢", "ゆめ")]), (found, used))

    def test_romaji_that_changes_over_the_same_kana_is_a_change_unless_accepted(self):
        other = read(romaji=("yume o mi ta", "yume wo mi ta"))
        found, _right, _used, romaji = self.changes(answer(read()), answer(other))
        self.assertFalse(romaji)
        self.assertEqual([("romaji", "", "", "ipadic", LINE, "yume o mita / yume wo mita", "yume o mi ta / yume wo mi ta"),
                          ("shown romaji", "", "", "ipadic", LINE, "yume o mita / yume wo mita", "yume o mi ta / yume wo mi ta")], found)
        self.assertEqual(([], True), self.changes(answer(read()), answer(other), accept_romaji=True)[::3])
        # Over other kana the romaji may change: the ruby says what changed.
        found = self.changes(answer(read()), answer(read(WRONG, romaji=("mu o mita", "mu wo mita"))))[0]
        self.assertEqual({"ruby", "shown"}, {change[0] for change in found})
        # A line with nothing right in it locks no romaji.
        nothing = [["夢", "む"], ["を", None], ["見", "め"], ["た", None]]
        self.assertEqual([], self.changes(answer(read(nothing), read(nothing)), answer(read(nothing, romaji=("x", "x")), read(nothing)))[0])

    def test_a_word_read_right_whose_reading_or_romaji_changes_is_a_change(self):
        heart = [["夢", "む", "mu", "mu"], *WORDS[1:]]
        found = self.changes(answer(read()), answer(read(words=heart)))[0]
        self.assertEqual([("shown words", "夢", "ゆめ", "ipadic", LINE, "ゆめ yume yume", "む mu mu"),
                          ("words", "夢", "ゆめ", "ipadic", LINE, "ゆめ yume yume", "む mu mu")], found)
        # Split otherwise, the word is not there.
        joined = [["夢を", "ゆめを", "yumeo", "yumewo"], WORDS[2]]
        self.assertIn(("words", "夢", "ゆめ", "ipadic", LINE, "ゆめ yume yume", None), self.changes(answer(read()), answer(read(words=joined)))[0])
        self.assertEqual([], self.changes(answer(read()), answer(read(words=heart)), {("夢", "ゆめ")})[0])

    def test_a_word_is_locked_only_where_its_kanji_read_right_and_its_reading_is_theirs(self):
        rights = wholelines.right_units(LINE, TRUTH, RIGHT)
        self.assertEqual({(0, 1): WORDS[0], (2, 4): WORDS[2]}, wholelines.right_words(LINE, RIGHT, rights, WORDS))
        # A word over half a part, one of kana alone, one whose reading is not the parts', one not in the line.
        words = [["夢を見", "ゆめをみ", "", ""], ["を", None, "o", "wo"], ["見た", "けんた", "", ""], ["空", "そら", "", ""]]
        self.assertEqual({}, wholelines.right_words(LINE, [["夢を見た", "ゆめをみた"]], rights, words[:1]))
        self.assertEqual({}, wholelines.right_words(LINE, RIGHT, rights, words[1:]))
        # A kanji read wrong locks no word over it.
        self.assertEqual({(2, 4): WORDS[2]}, wholelines.right_words(LINE, WRONG, wholelines.right_units(LINE, TRUTH, WRONG), WORDS))

    def test_a_song_main_reads_as_japanese_that_is_read_otherwise_is_a_change(self):
        found = self.changes(answer(read()), answer(read(), scripts=(None,)))[0]
        self.assertEqual([("script", "", "", "", LINE, "JAPANESE", "None")], found)
        # A song main did not read as Japanese may become Japanese; an empty song is named so too.
        self.assertEqual([], self.changes(answer(read(), scripts=("CHINESE",)), answer(read()))[0])
        self.assertEqual([("script", "", "", "", "", "JAPANESE", "CHINESE")],
                         self.changes(answer(read()), answer(read(), scripts=("CHINESE",)), songs=((),))[0])


class Command(unittest.TestCase):

    def repo(self, folder):
        """A repository with a readings source and a golden file on main, and a branch that changes the source."""
        repo = Path(folder) / "repo"
        git(Path(folder), "init", "-q", "-b", "main", "repo")
        write(repo, golden.READING_SOURCES[0], "one\n")
        write(repo, wholelines.HARNESS, "harness\n")
        write(repo, golden.GOLDEN_REL, "# Locked.\n# rows: 1\nrow\n")
        commit(repo, "test: start")
        git(repo, "checkout", "-q", "-b", "pr")
        write(repo, golden.READING_SOURCES[0], "two\n")
        commit(repo, "test: change a reading")
        return repo

    def audit(self, repo):
        folder = repo.parent / "audit-data"
        folder.mkdir(exist_ok=True)
        song(folder, "a", [(LINE, TRUTH)])
        return folder

    def record(self, repo, right=4, at="pr", lines=1):
        """The golden file on [at] names the audit, as lines --new-audit leaves it."""
        folder = self.audit(repo)
        digest = wholelines.audit_hash(wholelines.audit_lines(folder), wholelines.audit_songs(folder))
        git(repo, "checkout", "-q", at)
        write(repo, golden.GOLDEN_REL, f"# Locked.\n# audit: {lines} lines, {right} right readings, sha256 {digest}\n# rows: 1\nrow\n")
        commit(repo, "test: name the audit")
        git(repo, "checkout", "-q", "pr")
        if at == "main":
            git(repo, "merge", "-q", "main")

    def run_lines(self, repo, after=None, accepted=(), **options):
        """The command with a reader that answers [read()] in main's checkout and [after] in this one."""
        folder = self.audit(repo)
        trees = []

        def reader(where, both, entries, songs):
            self.assertEqual((folder, [(LINE, TRUTH)], [[LINE]]), (where, entries, songs))
            trees.extend(both)
            return [answer(read()), after or answer(read())]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = wholelines.lines(folder, accepted, base="main", repo=repo, read=reader, **options)
        # main's checkout is read in a worktree next to the audit folder, gone after, with this one in the same run.
        self.assertEqual(folder.parent, trees[0].parent.parent)
        self.assertEqual(repo, trees[1])
        self.assertFalse(trees[0].exists())
        return code, out.getvalue()

    def test_no_change_prints_the_line_for_the_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.record(repo)
            code, text = self.run_lines(repo)
            self.assertEqual(0, code, text)
            self.assertIn("1 whole lines and 1 songs", text)
            self.assertIn("right on main: 4 ruby, 4 shown, 4 words, 4 shown words; 0 change", text)
            digest = check_golden.readings_digest(repo, git(repo, "rev-parse", "main"), "HEAD")
            self.assertTrue(text.endswith(f"\ngolden: lines checked {digest}\n"), text)
            self.assertNotIn("golden: accept", text)

    def test_a_change_fails_until_accepted_and_the_accept_goes_in_the_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.record(repo)
            code, text = self.run_lines(repo, answer(read(WRONG)))
            self.assertEqual(1, code)
            self.assertIn("  ruby: 夢 ゆめ ipadic: 夢=ゆめ -> 夢=む  in 夢を見た\n", text)
            self.assertNotIn("golden: lines checked", text)
            # The file names what this checkout reads right, so the accepted change asks for it to be committed first.
            code, text = self.run_lines(repo, answer(read(WRONG)), [["夢", "ゆめ"]])
            self.assertEqual(1, code, text)
            self.assertIn("now names this audit", text)
            self.assertIn(", 3 right readings, ", (repo / golden.GOLDEN_REL).read_text())
            commit(repo, "test: name what this reads right")
            code, text = self.run_lines(repo, answer(read(WRONG)), [["夢", "ゆめ"]])
            self.assertEqual(0, code, text)
            self.assertIn("\ngolden: accept 夢 ゆめ\ngolden: lines checked ", text)

    def test_romaji_accepted_is_said_in_the_body(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.record(repo)
            other = answer(read(romaji=("a", "b")))
            self.assertEqual(1, self.run_lines(repo, other)[0])
            code, text = self.run_lines(repo, other, accept_romaji=True)
            self.assertEqual(0, code, text)
            self.assertIn("\ngolden: accept romaji\ngolden: lines checked ", text)

    def test_an_audit_folder_that_is_not_the_one_the_golden_file_names_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            # No audit named yet.
            with self.assertRaises(SystemExit) as stop:
                self.run_lines(repo)
            self.assertIn("names no audit", str(stop.exception))
            # Another audit, as one cut short or another folder.
            self.record(repo, lines=2)
            with self.assertRaises(SystemExit) as stop:
                self.run_lines(repo)
            self.assertIn("this one is empty, cut short or another", str(stop.exception))
            # As many lines, but other ones.
            self.record(repo)
            song(self.audit(repo), "a", [("空", [["空", "そら"]])])
            with self.assertRaises(SystemExit) as stop:
                wholelines.lines(self.audit(repo), [], base="main", repo=repo, read=None)
            self.assertIn("this one is empty, cut short or another", str(stop.exception))
            # An empty folder, even as a new audit.
            empty = repo.parent / "empty"
            (empty / "audit").mkdir(parents=True)
            with self.assertRaises(SystemExit) as stop:
                wholelines.lines(empty, [], base="main", repo=repo, read=None, new_audit=True)
            self.assertIn("has no lines", str(stop.exception))

    def test_a_new_audit_on_purpose_is_named_in_the_golden_file_and_committed(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.record(repo, lines=2)
            code, text = self.run_lines(repo, new_audit=True)
            self.assertEqual(1, code, text)
            self.assertIn("Commit it, then run lines again", text)
            written = (repo / golden.GOLDEN_REL).read_text()
            self.assertEqual((1, 4), wholelines.audit_line(written)[:2])
            # Once, just before the row count, and the rows stay.
            self.assertTrue(written.startswith("# Locked.\n# audit: 1 lines, 4 right readings, sha256 "), written)
            self.assertTrue(written.endswith("\n# rows: 1\nrow\n"), written)
            # Uncommitted, it is refused.
            with self.assertRaises(SystemExit) as stop:
                self.run_lines(repo)
            self.assertIn("Commit the readings change and the golden file first", str(stop.exception))
            commit(repo, "test: name the new audit")
            self.assertEqual(0, self.run_lines(repo)[0])

    def test_main_reading_another_number_right_on_its_own_audit_is_another_build(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            self.record(repo, right=5, at="main")
            with self.assertRaises(SystemExit) as stop:
                self.run_lines(repo)
            self.assertIn("main's code reads 4 readings right on these lines, and main's readings-golden.tsv says 5", str(stop.exception))

    def test_a_branch_without_main_in_it_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            git(repo, "checkout", "-q", "main")
            write(repo, "README.md", "newer\n")
            commit(repo, "test: main moves on")
            git(repo, "checkout", "-q", "pr")
            with self.assertRaises(SystemExit) as stop:
                wholelines.lines(self.audit(repo), [], base="main", repo=repo, read=None)
            self.assertIn("merge main first", str(stop.exception))

    def test_an_uncommitted_readings_change_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = self.repo(folder)
            write(repo, golden.READING_SOURCES[0], "three\n")
            with self.assertRaises(SystemExit) as stop:
                wholelines.lines(repo.parent, [], base="main", repo=repo, read=None)
            self.assertIn("Commit the readings change", str(stop.exception))

    def test_the_command_runs_it_on_the_data_folder(self):
        argv = ["readingsaudit.py", "lines", "--base", "origin/x", "--accept", "夢", "ゆめ", "--accept-romaji", "--new-audit"]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(wholelines, "lines", return_value=1) as lines, \
                mock.patch.object(readingsaudit, "data_dir", return_value=Path("/audit")), self.assertRaises(SystemExit) as stop:
            readingsaudit.main()
        self.assertEqual(1, stop.exception.code)
        self.assertEqual((Path("/audit"), [["夢", "ゆめ"]], "origin/x"), lines.call_args.args)
        self.assertEqual({"new_audit": True, "accept_romaji": True}, lines.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
