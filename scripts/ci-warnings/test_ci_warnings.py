import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ci_warnings  # noqa: E402


def line(message, job="build"):
    return f"{job}\tUNKNOWN STEP\t2026-11-10T21:25:43.3342070Z {message}\n"


class ClassifyTest(unittest.TestCase):
    def test_each_kind_is_named(self):
        cases = {
            "Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.": "gradle-deprecation",
            "w: file:///x/PracticeCardsTest.kt:1544:62 This call may have inconsistent results": "kotlin",
            "1:11:51 PM PIT >> WARNING : More threads at end of test (8) a(b.C) than start. (6)": "pit-threads",
            "1:11:51 PM PIT >> WARNING : Minion exited abnormally due to TIMED_OUT": "pit-minion",
            "hint: Using 'master' as the name for the initial branch. This default branch name": "git-hint",
            "##[warning]Node.js 20 is deprecated.": "actions",
            "(node:2256) [DEP0040] DeprecationWarning: The `punycode` module is deprecated.": "node-deprecation",
            "npm warn deprecated glob@7.2.3": "npm",
            "/x/a.py:3: DeprecationWarning: datetime.utcnow() is deprecated": "python",
            "/x/GLSLGenerator.cpp:791:42: warning: data argument not used by format string": "compiler",
            "Lint found 0 errors, 3 warnings": "lint",
            "WARNING: A restricted method in java.lang.System has been called": "jvm",
            "[build-stdout] [WARN] Failed to find dynload path": "other",
        }
        for message, kind in cases.items():
            with self.subTest(message):
                self.assertEqual(kind, ci_warnings.classify(message))

    def test_lines_that_only_mention_warnings_are_not_counted(self):
        for message in [
            "You can use '--warning-mode all' to show the individual deprecation warnings",
            "[Incubating] Problems report is available at: file:///x/problems-report.html",
            "hint: to use in all of your new repositories, which will suppress this warning,",
            "[command]/usr/bin/tar -x --warning=no-unknown-keyword -f -",
            "if-no-files-found: warn",
            "./gradlew -Dorg.gradle.warning.mode=all",
            "BUILD SUCCESSFUL, 0 warnings",
            "(Use `node --trace-deprecation ...` to show where the warning was created)",
            "Interpreted diagnostic query \"Extraction warnings\" (cpp/diagnostics/extraction-warnings)",
            "Tests passed.",
        ]:
            with self.subTest(message):
                self.assertIsNone(ci_warnings.classify(message))

    def test_a_script_echoed_before_it_runs_is_not_counted(self):
        self.assertIsNone(ci_warnings.classify('\x1b[36;1mecho "::warning:: Gradle validation failed"\x1b[0m'))
        self.assertIsNone(ci_warnings.classify('^[[36;1m  echo "::warning:: Gradle validation failed"^[[0m'))

    def test_colors_are_ignored(self):
        self.assertEqual("kotlin", ci_warnings.classify("\x1b[33mw: file:///x/A.kt:1:1 Unused\x1b[0m"))


class CountTest(unittest.TestCase):
    def test_warnings_are_counted_per_job_and_kind(self):
        found = ci_warnings.count([
            line("w: file:///x/A.kt:1:1 one", job="test"),
            line("w: file:///x/A.kt:2:1 two", job="test"),
            line("Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.", job="lint"),
            line("BUILD SUCCESSFUL in 3m", job="lint"),
            "a line that isn't from a log\n",
        ])
        self.assertEqual({("test", "kotlin"): 2, ("lint", "gradle-deprecation"): 1},
                         {key: len(messages) for key, messages in found.items()})

    def test_the_byte_order_mark_on_a_jobs_first_line_is_skipped(self):
        found = ci_warnings.count(["build\tSet up job\t﻿2026-11-10T21:25:43.3342070Z ##[warning]Old\n"])
        self.assertEqual(["##[warning]Old"], found[("build", "actions")])


class MainTest(unittest.TestCase):
    def run_main(self, *args):
        out = io.StringIO()
        old, sys.stdout = sys.stdout, out
        try:
            status = ci_warnings.main(list(args))
        finally:
            sys.stdout = old
        return status, out.getvalue()

    def test_a_saved_log_is_counted_and_fail_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp, "run.log")
            log.write_text(line("##[warning]Node.js 20 is deprecated.") + line("w: file:///x/A.kt:1:1 one"))
            status, out = self.run_main("--log", str(log), "--lines")
            self.assertEqual(0, status)
            self.assertIn("## total: 2", out)
            self.assertIn("1x ##[warning]Node.js N is deprecated.", out)
            status, _ = self.run_main("--log", str(log), "--fail")
            self.assertEqual(1, status)

    def test_a_clean_log_passes_with_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp, "run.log")
            log.write_text(line("BUILD SUCCESSFUL"))
            status, out = self.run_main("--log", str(log), "--fail")
            self.assertEqual(0, status)
            self.assertIn("## total: 0", out)


if __name__ == "__main__":
    unittest.main()
