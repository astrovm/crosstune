"""The workflows reach Google Play only through .github/play/play.sh, which keeps a review in progress.

fastlane supply on its own commits with Play's default, which cancels a review in progress. play.sh loads
review_guard.rb first. .github/play/test/play_test.rb tests play.sh itself.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile
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

    def test_a_pre_release_is_never_the_latest_and_never_goes_to_play(self):
        text = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        steps = jobs(text)
        self.assertIn("prerelease: ${{ steps.tag.outputs.prerelease }}", steps["build"])
        self.assertIn('if [[ "$TAG" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+$ ]]; then\n            echo "prerelease=false"', steps["build"])
        self.assertIn('if [[ "$PRERELEASE" == "true" ]]; then\n            args+=(--prerelease)', steps["publish"])
        self.assertIn("if: needs.build.outputs.prerelease == 'false'", steps["play"])

    def test_release_yml_and_play_sh_agree_on_which_tags_are_pre_releases(self):
        build = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))["build"]
        valid = re.search(r'if \[\[ ! "\$TAG" =~ (\S+) \]\]', build).group(1)
        plain = re.search(r'if \[\[ "\$TAG" =~ (\S+) \]\]; then\n +echo "prerelease=false"', build).group(1)
        play_sh = (ROOT / ".github/play/play.sh").read_text(encoding="utf-8")
        skipped = re.search(r'"\$\{2:-\}" =~ (\S+) \]\]', play_sh).group(1)
        tags = {"v2.27.0": False, "v2.27.0-rc.1": True, "v2.27.0.1": True, "v2.27.0-rc": True, "v10.0.12": False}
        for tag, pre_release in tags.items():
            with self.subTest(tag=tag):
                self.assertTrue(re.search(valid, tag))
                self.assertEqual(pre_release, not re.search(plain, tag))
                self.assertEqual(pre_release, bool(re.search(skipped, tag)))
        for tag in ("v2.27", "v2.27.0.", "v2.27.0-", "v2.27.0+x", "2.27.0", "v2.27.0 "):
            with self.subTest(tag=tag):
                self.assertFalse(re.search(valid, tag))


def publish_step():
    """The script of release.yml's "Publish GitHub release" step."""
    job = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))["publish"]
    step = job[job.index("- name: Publish GitHub release"):]
    lines = step[step.index("run: |\n") + len("run: |\n"):].split("\n")
    script = []
    for line in lines:
        if line.strip() and not line.startswith(" " * 10):
            break
        script.append(line[10:])
    return "\n".join(script)


# A gh that keeps the arguments of `gh release create`, and answers for the latest release with
# FAKE_LATEST, or as GitHub does with no release yet, or with FAKE_GH_FAILS, as on an outage.
FAKE_GH = r"""#!/usr/bin/env bash
if [[ "$1 $2" == "release create" ]]; then
  printf '%s\n' "$@" > "$FAKE_GH_CREATE"
elif [[ "$1" == api && "$2" == "repos/$GITHUB_REPOSITORY/releases/latest" ]]; then
  if [[ -n "${FAKE_GH_FAILS:-}" ]]; then
    echo "gh: Server Error (HTTP 500)" >&2
    exit 1
  elif [[ -z "${FAKE_LATEST:-}" ]]; then
    echo "gh: Not Found (HTTP 404)" >&2
    exit 1
  fi
  echo "$FAKE_LATEST"
else
  echo "fake gh doesn't know: $*" >&2
  exit 1
fi
"""


class PublishTest(unittest.TestCase):
    def publish(self, tag, latest="", fails=False):
        with tempfile.TemporaryDirectory() as work:
            gh = Path(work, "gh")
            gh.write_text(FAKE_GH, encoding="utf-8")
            gh.chmod(0o755)
            create = Path(work, "create")
            env = {
                "PATH": f"{work}:{os.environ['PATH']}",
                "TAG": tag,
                "PRERELEASE": "false" if re.fullmatch(r"v\d+\.\d+\.\d+", tag) else "true",
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REPOSITORY": "owner/app",
                "CERT_SHA256": "00",
                "FAKE_GH_CREATE": str(create),
                "FAKE_LATEST": latest,
                "FAKE_GH_FAILS": "1" if fails else "",
            }
            run = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", publish_step()], env=env, cwd=work,
                                 capture_output=True, text=True, check=False)
            sent = create.read_text(encoding="utf-8").split("\n") if create.exists() else None
            return run, sent

    def test_a_backport_never_becomes_the_latest_release(self):
        run, sent = self.publish("v2.26.1", latest="v2.27.0")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("--latest=false", sent)

    def test_a_newer_version_becomes_the_latest_release(self):
        for tag, latest in (("v2.28.0", "v2.27.0"), ("v2.10.0", "v2.9.0"), ("v3.0.0", "v2.99.99"), ("v1.0.0", "")):
            with self.subTest(tag=tag, latest=latest):
                run, sent = self.publish(tag, latest=latest)
                self.assertEqual(0, run.returncode, run.stderr)
                self.assertNotIn("--latest=false", sent)
                self.assertNotIn("--prerelease", sent)

    def test_a_pre_release_is_never_the_latest_release(self):
        run, sent = self.publish("v2.28.0-rc.1", latest="v2.27.0")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("--prerelease", sent)

    def test_no_release_is_made_when_github_cant_say_which_is_the_latest(self):
        run, sent = self.publish("v2.26.1", fails=True)
        self.assertNotEqual(0, run.returncode)
        self.assertIsNone(sent)


if __name__ == "__main__":
    unittest.main()
