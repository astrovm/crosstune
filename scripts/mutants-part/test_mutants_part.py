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
                "A.kt:2  timed out: loop",
                "A.kt:12  no coverage: removed call",
                "B.kt:10  survived: negated conditional",
            ],
        )


if __name__ == "__main__":
    unittest.main()
