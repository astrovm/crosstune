"""Exercise the release guard against version bumps and real metadata files."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest

import check_changelog


class ChangelogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build = self.root / "app/build.gradle.kts"
        self.build.parent.mkdir()
        self.build.write_text("versionCode = 2080900\n", encoding="utf-8")
        self.folder = self.root / "fastlane/metadata/android/en-US/changelogs"
        self.folder.mkdir(parents=True)
        self.notes = self.folder / "2080900.txt"

    def test_matching_nonempty_changelog_passes(self):
        self.notes.write_text("Cleaner song titles.\n", encoding="utf-8")
        self.assertEqual(check_changelog.check_changelog(self.root), self.notes)

    def test_missing_2_8_9_changelog_fails_even_with_previous_notes(self):
        (self.folder / "2080800.txt").write_text("Previous release.", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing Play Store changelog.*2080900.txt"):
            check_changelog.check_changelog(self.root)

    def test_version_bump_requires_new_notes(self):
        self.notes.write_text("Previous release.", encoding="utf-8")
        self.build.write_text("versionCode = 2081000\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing Play Store changelog.*2081000.txt"):
            check_changelog.check_changelog(self.root)
        newer_notes = self.folder / "2081000.txt"
        newer_notes.write_text("Current release.", encoding="utf-8")
        self.assertEqual(check_changelog.check_changelog(self.root), newer_notes)

    def test_empty_and_whitespace_only_notes_fail(self):
        for text in ("", " \t\r\n", "\u2003\n"):
            with self.subTest(text=text):
                self.notes.write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Empty Play Store changelog"):
                    check_changelog.check_changelog(self.root)

    def test_other_locale_and_default_notes_do_not_replace_english_notes(self):
        spanish = self.root / "fastlane/metadata/android/es/changelogs"
        spanish.mkdir(parents=True)
        (spanish / self.notes.name).write_text("Títulos más claros.", encoding="utf-8")
        (self.folder / "default.txt").write_text("Default release.", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing Play Store changelog"):
            check_changelog.check_changelog(self.root)

    def test_directory_is_not_a_changelog(self):
        self.notes.mkdir()
        with self.assertRaisesRegex(ValueError, "Missing Play Store changelog"):
            check_changelog.check_changelog(self.root)

    def test_indentation_crlf_and_inline_comment_are_supported(self):
        self.build.write_bytes(b"    versionCode = 2080900 // Current release\r\n")
        self.notes.write_text("Cleaner song titles.", encoding="utf-8")
        self.assertEqual(check_changelog.check_changelog(self.root), self.notes)

    def test_missing_duplicate_or_invalid_version_code_fails(self):
        for source in (
            "// versionCode = 2080900\n",
            "versionCode = 2080900\nversionCode = 2081000\n",
            "versionCode = releaseCode\n",
            "versionCode = 0\n",
            "versionCode = -1\n",
            "versionCode = 2080900 + 100\n",
            "versionCode = ../outside\n",
        ):
            with self.subTest(source=source):
                self.build.write_text(source, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "versionCode"):
                    check_changelog.check_changelog(self.root)

    def test_cli_failure_reports_actionable_github_error(self):
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_changelog.main(self.root), 1)
        self.assertIn("::error::Missing Play Store changelog: add", errors.getvalue())
        self.assertIn("2080900.txt", errors.getvalue())

    def test_cli_success_reports_checked_file(self):
        self.notes.write_text("Cleaner song titles.", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(check_changelog.main(self.root), 0)
        self.assertIn(str(self.notes), output.getvalue())

    def test_cli_unreadable_metadata_fails(self):
        self.build.unlink()
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_changelog.main(self.root), 1)
        self.assertIn("::error::", errors.getvalue())

    def test_cli_invalid_utf8_notes_fail(self):
        self.notes.write_bytes(b"\xff")
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check_changelog.main(self.root), 1)
        self.assertIn("::error::", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
