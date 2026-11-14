import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import regextable

TABLE = """# engine\tICU 1.0, Android 9, API 28
class\t\\s\t9-d,20
class\t.\t0-9,b-c,e-10ffff
class\t\\d\t30-39
sfold\t-\t41>61,42>62
multi\tdf\t73 73
ends\ta\t1 2
"""


class RegexTableTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())

    def tearDown(self):
        for path in sorted(self.work.rglob("*"), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        self.work.rmdir()

    def written(self, name, text):
        path = self.work / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_runs_of_code_points_read_back_as_they_were_written(self):
        self.assertEqual({9, 10, 11, 0x20, 0x10FFFF}, regextable.codes("9-b,20,10ffff"))
        self.assertEqual("9-b,20,10ffff", regextable.ranges(regextable.codes("9-b,20,10ffff")))
        self.assertEqual("", regextable.ranges(set()))
        self.assertEqual(set(), regextable.codes(""))

    def test_a_long_set_is_shown_cut_short(self):
        self.assertEqual("1 3 ...(3 runs)", regextable.shown({1, 3, 5}, most=2))
        self.assertEqual("A-B", regextable.shown({10, 11}))
        self.assertEqual("", regextable.shown(set()))

    def test_a_table_is_read_by_kind_and_name_with_its_engine(self):
        rows, engine = regextable.load(self.written("t.tsv", TABLE))
        self.assertEqual("ICU 1.0, Android 9, API 28", engine)
        self.assertEqual(["9-d,20"], rows[("class", "\\s")])
        self.assertEqual(["1 2"], rows[("ends", "a")])
        self.assertEqual("28", regextable.api(engine))

    def test_two_tables_differ_by_class_ends_and_rows_only_one_has(self):
        other = TABLE.replace("9-d,20", "9-d,20,a0").replace("ends\ta\t1 2", "ends\ta\t1") + "case\tx\ty\n"
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            count = regextable.diff(self.written("a.tsv", TABLE), self.written("b.tsv", other))
        self.assertEqual(3, count)
        said = out.getvalue().splitlines()
        self.assertIn("class \\s: A only 0:  | B only 1: A0", said)
        self.assertIn("ends U+000A: A 1 2 | B 1", said)
        self.assertIn("case x: B only", said)
        self.assertEqual("3 rows differ", said[-1])

    def test_the_kept_table_makes_the_kotlin_the_app_and_tests_read(self):
        # PhoneRegexTable.kt and PhoneRegexSets.kt are made from android-37.tsv: made again, they're the same.
        app, tests = self.work / "PhoneRegexTable.kt", self.work / "PhoneRegexSets.kt"
        with contextlib.redirect_stdout(io.StringIO()):
            regextable.generate(regextable.TABLES / "android-37.tsv", app, tests)
        self.assertEqual(regextable.KOTLIN.read_text(encoding="utf-8"), app.read_text(encoding="utf-8"))
        self.assertEqual(regextable.TEST_KOTLIN.read_text(encoding="utf-8"), tests.read_text(encoding="utf-8"))

    def test_sets_are_taken_from_their_rows_or_what_a_row_leaves_out(self):
        app, tests = self.work / "app.kt", self.work / "tests.kt"
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            regextable.generate(self.written("t.tsv", TABLE), app, tests)
        made = app.read_text(encoding="utf-8")
        self.assertIn('internal const val PHONE_ENGINE = "ICU 1.0, Android 9, API 28"', made)
        self.assertIn('internal const val PHONE_SPACE = "9-d,20"', made)
        # What `.` doesn't read.
        self.assertIn('internal const val PHONE_LINE_BREAK = "a,d"', made)
        self.assertIn('internal const val PHONE_SIMPLE_FOLDS = "41:61,42:62"', made)
        self.assertIn('internal const val PHONE_FULL_FOLDS = "df:73 73"', made)
        # Only the tests read \w, and this table has none.
        self.assertNotIn("PHONE_WORD ", made)
        self.assertIn('internal const val PHONE_WORD = ""', tests.read_text(encoding="utf-8"))
        self.assertIn("PHONE_WORD: no \\w in", out.getvalue())

    def test_a_patterns_key_is_patterns_probes(self):
        # ChatGpt.kt's `\d+`, as PatternsProbe keys it in the kept tables.
        kept = regextable.digests(regextable.TABLES / "patterns-android-37.tsv")[1]
        self.assertIn(regextable.key("\\d+", ""), kept)
        self.assertEqual(regextable.key("a", "MULTILINE,IGNORE_CASE"), regextable.key("a", "IGNORE_CASE,MULTILINE,"))
        self.assertNotEqual(regextable.key("a", "IGNORE_CASE"), regextable.key("a", ""))

    def test_a_listed_pattern_is_read_with_its_escapes(self):
        self.assertEqual("a\tb\n\\c", regextable.unescaped("a\\tb\\n\\\\c"))
        listed = regextable.listed(self.written("list.tsv", "A.kt:3\tIGNORE_CASE\t\\\\s\\t\nB.kt:9\t\tx\n"))
        self.assertEqual({regextable.key("\\s\t", "IGNORE_CASE"): "A.kt:3", regextable.key("x", ""): "B.kt:9"}, listed)

    def test_a_digest_table_is_read_with_its_engine(self):
        engine, read = regextable.digests(self.written("d.tsv", "# engine\tICU 2, API 26\nk1\td1\nk2\tthrows\n"))
        self.assertEqual("ICU 2, API 26", engine)
        self.assertEqual({"k1": "d1", "k2": "throws"}, read)

    def test_the_report_names_each_pattern_the_jvm_reads_otherwise(self):
        head = {"k1": "A.kt:1", "k2": "B.kt:2", "k3": "C.kt:3"}
        phone = {"k1": "x", "k2": "y", "k3": "z"}
        there = {"k1": "x", "k2": "other", "k3": "other"}
        lines = regextable.report(head, {"k1", "k2"}, phone, there, "main")
        self.assertEqual(["B.kt:2: the JVM reads it otherwise", "C.kt:3: the JVM reads it otherwise (touched)",
                          "3 patterns, 1 touched since main; the JVM reads 2 otherwise than the phone, 1 of them touched"], lines)

    def test_a_phone_is_never_probed(self):
        with self.assertRaises(SystemExit):
            regextable.emulator("R5CT1234")
        self.assertEqual("emulator-5556", regextable.emulator("emulator-5556"))


if __name__ == "__main__":
    unittest.main()
