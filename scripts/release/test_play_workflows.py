"""The workflows reach Google Play only through .github/play/play.sh, which keeps a review in progress.

fastlane supply on its own commits with Play's default, which cancels a review in progress. play.sh loads
review_guard.rb first. .github/play/test/play_test.rb tests play.sh itself.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"
PLAY_KEY = "secrets.PLAY_SERVICE_ACCOUNT_JSON"


def jobs(text):
    """Each job's name and text, from a workflow's jobs: block."""
    block = text[text.index("\njobs:\n"):]
    parts = re.split(r"\n  (?=[A-Za-z0-9_-]+:\n)", block)
    return {part.split(":", 1)[0]: part for part in parts[1:]}


def play_jobs():
    for path in sorted(WORKFLOWS.glob("*.yml")):
        for name, job in jobs(path.read_text(encoding="utf-8")).items():
            if PLAY_KEY in job:
                yield path.name, name, job


class PlayWorkflowsTest(unittest.TestCase):
    def test_the_play_key_is_in_the_three_play_jobs_only(self):
        found = sorted((workflow, job) for workflow, job, _ in play_jobs())
        self.assertEqual(
            [("promote.yml", "production"), ("release.yml", "play"), ("store-listing.yml", "listing")], found)

    def test_every_play_job_goes_through_play_sh(self):
        for workflow, name, job in play_jobs():
            with self.subTest(workflow=workflow, job=name):
                self.assertRegex(job, r"\.github/play/play\.sh (release|production|listing)\b")
                self.assertNotRegex(job, r"\bfastlane (supply|run)\b|bundle exec|gem \"fastlane\"")

    def test_no_build_code_is_checked_out_where_the_play_key_is(self):
        allowed = {".github/play", "fastlane/metadata/android", "fastlane/metadata/android/en-US/changelogs"}
        for workflow, name, job in play_jobs():
            with self.subTest(workflow=workflow, job=name):
                self.assertNotIn("submodules", job)
                self.assertNotIn("setup-gradle", job)
                checkouts = job.split("uses: actions/checkout@")[1:]
                self.assertTrue(checkouts)
                for checkout in checkouts:
                    step = checkout.split("\n      - ")[0]
                    self.assertIn("sparse-checkout-cone-mode: false", step)
                    match = re.search(r"sparse-checkout: (\|\n((?: {12}\S.*\n)+)|(\S+))", step + "\n")
                    self.assertIsNotNone(match, step)
                    paths = (match.group(2) or match.group(3)).split()
                    self.assertTrue(set(paths) <= allowed, paths)
                    self.assertIn("persist-credentials: false", step)

    def test_play_jobs_run_in_the_release_environment_one_at_a_time(self):
        for workflow, name, job in play_jobs():
            with self.subTest(workflow=workflow, job=name):
                self.assertIn("environment: release", job)
                self.assertIn("permissions: {}", job)
                self.assertIn("concurrency:\n      group: google-play\n      cancel-in-progress: false", job)

    def test_the_listing_is_sent_by_hand_only(self):
        text = (WORKFLOWS / "store-listing.yml").read_text(encoding="utf-8")
        triggers = text[text.index("\non:\n"):text.index("\npermissions:")]
        self.assertNotIn("push:", triggers)
        self.assertIn("workflow_dispatch:", triggers)
        for name in ("ref:", "languages:", "check_only:"):
            self.assertIn(name, triggers)

    def test_production_is_sent_by_hand_only(self):
        text = (WORKFLOWS / "promote.yml").read_text(encoding="utf-8")
        triggers = text[text.index("\non:\n"):text.index("\npermissions:")]
        self.assertEqual(["workflow_dispatch:"], re.findall(r"^  (\S+:)$", triggers, re.M))
        self.assertIn("version:", triggers)
        self.assertIn('.github/play/play.sh production "$VERSION"', text)

    def test_a_release_names_no_track_itself(self):
        job = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))["play"]
        self.assertIn("PLAY_TRACKS: ${{ vars.PLAY_TRACKS }}", job)
        self.assertNotIn("production", job.split("run: |", 1)[1].replace("promote.yml sends a release to production", ""))


if __name__ == "__main__":
    unittest.main()
