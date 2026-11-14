"""hotfix.py on a real Git repository with an origin: the steps of a fix to an older version."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import unittest.mock

import hotfix

GRADLE = """    defaultConfig {{
        // For X.Y.Z use X*1000000 + Y*10000 + Z*100. Update both values for each release.
        versionCode = {code}
        versionName = "{name}"
    }}
"""
NOTES = "fastlane/metadata/android/en-US/changelogs"
# Fake people for the commits made here.
GIT_ENV = {"GIT_AUTHOR_NAME": "test", "GIT_AUTHOR_EMAIL": "test@example.invalid",
           "GIT_COMMITTER_NAME": "test", "GIT_COMMITTER_EMAIL": "test@example.invalid",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


class HotfixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = dict(os.environ, **GIT_ENV)
        patcher = unittest.mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        base = Path(self.tmp.name)
        self.origin = base / "origin.git"
        self.git(base, "init", "--quiet", "--bare", "--initial-branch=main", str(self.origin))
        self.repo = base / "repo"
        self.git(base, "clone", "--quiet", str(self.origin), str(self.repo))
        self.git(self.repo, "switch", "--quiet", "--create", "main")
        # v2.26.0, from before the release went through .github/play.
        self.write("app/build.gradle.kts", GRADLE.format(code=2260000, name="2.26.0"))
        self.write(f"{NOTES}/2260000.txt", "Faster lyrics.\n")
        self.write(".github/workflows/release.yml", "old: sends to production with supply\n")
        self.commit("v2.26.0")
        self.git(self.repo, "tag", "v2.26.0")
        # main since: v2.27.0, with the release tooling of today.
        self.write("app/build.gradle.kts", GRADLE.format(code=2270000, name="2.27.0"))
        self.write(f"{NOTES}/2270000.txt", "Chords.\n")
        self.write(".github/workflows/release.yml", "new: through play.sh\n")
        self.write(".github/play/play.sh", "keeps the review\n")
        self.write("scripts/release/hotfix.py", "this script\n")
        self.commit("v2.27.0")
        self.git(self.repo, "tag", "v2.27.0")
        self.git(self.repo, "push", "--quiet", "origin", "main", "v2.26.0", "v2.27.0")

    def git(self, cwd, *args):
        return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")

    def commit(self, message):
        self.git(self.repo, "add", "--all")
        self.git(self.repo, "commit", "--quiet", "--message", message)

    def run_hotfix(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = hotfix.main(list(args), self.repo)
        return code, out.getvalue() + err.getvalue()

    def show(self, ref, path):
        return self.git(self.repo, "show", f"{ref}:{path}")

    def test_start_branches_from_the_tag_with_mains_release_tooling_and_the_new_version(self):
        code, output = self.run_hotfix("start", "v2.26.0", "2.26.1")
        self.assertEqual(0, code, output)

        self.assertEqual("hotfix/v2.26.1\n", self.git(self.repo, "branch", "--show-current"))
        self.assertEqual(self.git(self.repo, "rev-parse", "v2.26.0"), self.git(self.repo, "rev-parse", "HEAD~1"))
        # A tag runs its own commit's release workflow: the old one would send to production and cancel a review.
        self.assertEqual("new: through play.sh\n", self.show("HEAD", ".github/workflows/release.yml"))
        self.assertEqual("keeps the review\n", self.show("HEAD", ".github/play/play.sh"))
        # The old tag has no hotfix.py, and check runs on the branch.
        self.assertEqual("this script\n", self.show("HEAD", "scripts/release/hotfix.py"))
        self.assertEqual(GRADLE.format(code=2260100, name="2.26.1"), self.show("HEAD", "app/build.gradle.kts"))
        self.assertIn(f"{NOTES}/2260100.txt", output)

    def test_start_refuses_a_version_that_isnt_above_its_tag_or_has_a_tag(self):
        for version, message in (("2.26.0", "2.26.0 isn't above v2.26.0"),
                                 ("2.25.9", "2.25.9 isn't above v2.26.0"),
                                 ("2.27.0", "v2.27.0 is a tag already"),
                                 ("2.26", "'2.26' must look like 2.26.1")):
            with self.subTest(version=version):
                code, output = self.run_hotfix("start", "v2.26.0", version)
                self.assertEqual(1, code)
                self.assertIn(message, output)
                self.assertEqual("main\n", self.git(self.repo, "branch", "--show-current"))

        code, output = self.run_hotfix("start", "v2.25.0", "2.25.1")
        self.assertEqual(1, code)
        self.assertIn("v2.25.0 isn't a tag on origin", output)

    def test_start_refuses_a_checkout_with_changes(self):
        self.write("app/build.gradle.kts", "changed\n")
        code, output = self.run_hotfix("start", "v2.26.0", "2.26.1")
        self.assertEqual(1, code)
        self.assertIn("This checkout has changes", output)
        self.assertEqual("main\n", self.git(self.repo, "branch", "--show-current"))

    def test_check_names_each_step_left(self):
        self.assertEqual(0, self.run_hotfix("start", "v2.26.0", "2.26.1")[0])
        # Someone took the old workflow back on the branch.
        self.write(".github/workflows/release.yml", "old: sends to production with supply\n")
        self.commit("fix")
        code, output = self.run_hotfix("check")
        self.assertEqual(1, code)
        self.assertIn("::error::.github/workflows/release.yml isn't main's", output)
        self.assertIn(f"::error::{NOTES}/2260100.txt isn't on this branch", output)
        self.assertIn(f"::error::{NOTES}/2260100.txt isn't on origin's main", output)
        self.assertNotIn("git tag", output)

    def test_check_gives_the_tag_when_the_branch_is_ready(self):
        self.assertEqual(0, self.run_hotfix("start", "v2.26.0", "2.26.1")[0])
        self.write(f"{NOTES}/2260100.txt", "Lyrics stay in time again.\n")
        self.commit("fix(lyrics): keep lyrics in time")
        # The same notes on main, which promote.yml reads.
        self.git(self.repo, "switch", "--quiet", "main")
        self.write(f"{NOTES}/2260100.txt", "Lyrics stay in time again.\n")
        self.commit("docs: notes for 2.26.1")
        self.git(self.repo, "push", "--quiet", "origin", "main")
        self.git(self.repo, "switch", "--quiet", "hotfix/v2.26.1")

        code, output = self.run_hotfix("check")
        self.assertEqual(0, code, output)
        self.assertIn("git tag v2.26.1 && git push origin hotfix/v2.26.1 v2.26.1", output)

    def test_check_reads_mains_tooling_as_origin_has_it_now(self):
        self.assertEqual(0, self.run_hotfix("start", "v2.26.0", "2.26.1")[0])
        self.write(f"{NOTES}/2260100.txt", "Fixed.\n")
        self.commit("fix: it")
        other = Path(self.tmp.name, "other")
        self.git(Path(self.tmp.name), "clone", "--quiet", str(self.origin), str(other))
        (other / ".github/play/play.sh").write_text("changed on main since\n", encoding="utf-8")
        (other / NOTES / "2260100.txt").write_text("Fixed.\n", encoding="utf-8")
        self.git(other, "add", "--all")
        self.git(other, "commit", "--quiet", "--message", "main moved")
        self.git(other, "push", "--quiet", "origin", "main")

        code, output = self.run_hotfix("check")
        self.assertEqual(1, code)
        self.assertIn("::error::.github/play isn't main's", output)
        self.assertIn("git checkout origin/main -- .github/workflows/release.yml .github/play", output)


if __name__ == "__main__":
    unittest.main()
