import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import mutants_part


class Parts(unittest.TestCase):
    def test_every_line_is_in_exactly_one_part(self):
        with tempfile.TemporaryDirectory() as root:
            sources = Path(root)
            (sources / "com/astrovm/crosstune/ui").mkdir(parents=True)
            (sources / "com/astrovm/crosstune/B.kt").write_text("b1\nb2\n")
            (sources / "com/astrovm/crosstune/A.kt").write_text("a1\na2\na3\n")
            (sources / "com/astrovm/crosstune/ui/C.kt").write_text("c1\n")
            (sources / "com/astrovm/crosstune/notes.txt").write_text("not code\n")
            lines = mutants_part.all_lines(sources)
        self.assertEqual(
            lines,
            [
                "com/astrovm/crosstune/A.kt:1",
                "com/astrovm/crosstune/A.kt:2",
                "com/astrovm/crosstune/A.kt:3",
                "com/astrovm/crosstune/B.kt:1",
                "com/astrovm/crosstune/B.kt:2",
                "com/astrovm/crosstune/ui/C.kt:1",
            ],
        )
        for n in (1, 4, 6, 9):
            parts = [mutants_part.part(lines, k, n) for k in range(n)]
            self.assertEqual(sum(parts, []), lines, n)
            self.assertLessEqual(max(map(len, parts)) - min(map(len, parts)), 1, n)

    def test_a_part_out_of_range_is_refused(self):
        for k in (-1, 4):
            with self.assertRaises(ValueError):
                mutants_part.part(["a"], k, 4)


class Lived(unittest.TestCase):
    def test_same_line_mutants_remain_distinct(self):
        report = """<mutations>
          <mutation status="SURVIVED"><sourceFile>A.kt</sourceFile><mutatedClass>pkg.A</mutatedClass><mutatedMethod>read</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>10</lineNumber><indexes><index>2</index></indexes><blocks><block>0</block></blocks><description>changed boundary</description></mutation>
          <mutation status="SURVIVED"><sourceFile>A.kt</sourceFile><mutatedClass>pkg.A</mutatedClass><mutatedMethod>read</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>10</lineNumber><indexes><index>4</index></indexes><blocks><block>0</block></blocks><description>changed boundary</description></mutation>
        </mutations>"""
        with tempfile.NamedTemporaryFile("w", suffix=".xml") as file:
            file.write(report)
            file.flush()
            rows, total = mutants_part.lived(Path(file.name))
        self.assertEqual(total, 2)
        self.assertEqual(len(set(rows)), 2)

    def test_what_no_test_caught_is_listed_by_file_and_line(self):
        report = """<mutations>
          <mutation status='KILLED'><sourceFile>A.kt</sourceFile><lineNumber>3</lineNumber><description>killed</description></mutation>
          <mutation status='SURVIVED'><sourceFile>B.kt</sourceFile><lineNumber>10</lineNumber><description>negated conditional</description></mutation>
          <mutation status='NO_COVERAGE'><sourceFile>A.kt</sourceFile><lineNumber>12</lineNumber><description>removed call</description></mutation>
          <mutation status='TIMED_OUT'><sourceFile>A.kt</sourceFile><lineNumber>2</lineNumber><description>loop</description></mutation>
        </mutations>"""
        with tempfile.NamedTemporaryFile("w", suffix=".xml") as file:
            file.write(report)
            file.flush()
            rows, total = mutants_part.lived(Path(file.name))
        self.assertEqual(total, 4)
        self.assertEqual(
            rows,
            [
                "A.kt:2  timed out: loop [class=None method=NoneNone mutator=None index= block=]",
                "A.kt:12  no coverage: removed call [class=None method=NoneNone mutator=None index= block=]",
                "B.kt:10  survived: negated conditional [class=None method=NoneNone mutator=None index= block=]",
            ],
        )

    def test_every_change_that_was_not_killed_is_listed(self):
        # A change that ran out of memory or broke the run isn't killed either: it must not vanish.
        statuses = ["MEMORY_ERROR", "RUN_ERROR", "NON_VIABLE", "NOT_STARTED", "STARTED"]
        report = "<mutations><mutation status='KILLED'><sourceFile>A.kt</sourceFile><lineNumber>1</lineNumber><description>k</description></mutation>"
        report += "".join(f"<mutation status='{s}'><sourceFile>B.kt</sourceFile><lineNumber>{i + 1}</lineNumber><description>d</description></mutation>" for i, s in enumerate(statuses))
        with tempfile.NamedTemporaryFile("w", suffix=".xml") as file:
            file.write(report + "</mutations>")
            file.flush()
            rows, total = mutants_part.lived(Path(file.name))
        self.assertEqual(total, 6)
        self.assertEqual([row.split(": ")[0] for row in rows], [f"B.kt:{i + 1}  {s.lower().replace('_', ' ')}" for i, s in enumerate(statuses)])


class Branch(unittest.TestCase):
    """scripts/mutants.py, for a pull request, lists the same changes the weekly run does."""

    def test_a_memory_error_is_listed_and_fails_the_run(self):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(root)]))
        (root / "gradle.sh").write_text(
            f"mkdir -p {root}/r && cat > {root}/r/mutations.xml <<'EOF'\n"
            "<mutations><mutation status='MEMORY_ERROR'><sourceFile>M.kt</sourceFile><lineNumber>741</lineNumber><description>oom</description></mutation></mutations>\nEOF\n"
        )
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import mutants

        saved = (mutants.LINES, mutants.REPORT, mutants.changed_lines)
        self.addCleanup(lambda: [setattr(mutants, name, value) for name, value in zip(("LINES", "REPORT", "changed_lines"), saved)])
        mutants.LINES, mutants.REPORT, mutants.changed_lines = root / "lines.txt", root / "r/mutations.xml", lambda base: ["M.kt:741"]
        out = io.StringIO()
        sys.argv = ["mutants.py", "--gradle", f"sh {root}/gradle.sh"]
        with contextlib.redirect_stdout(out):
            status = mutants.main()
        self.assertEqual(status, 1)
        self.assertIn("M.kt:741  memory error: oom", out.getvalue())
        self.assertIn("0 of 1 changes caught by a test.", out.getvalue())


if __name__ == "__main__":
    unittest.main()
