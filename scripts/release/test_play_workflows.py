"""The workflows reach Google Play only through .github/play/play.sh, which keeps a review in progress.

fastlane supply on its own commits with Play's default, which cancels a review in progress. play.sh loads
review_guard.rb first. .github/play/test/play_test.rb tests play.sh itself.
"""
import hashlib
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"
# The repository secret. Each Play job hands it to play.sh as PLAY_KEY_JSON.
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
    def test_release_keeps_the_supported_signature_and_uses_checked_verifier_wheels(self):
        release = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))
        self.assertIn("--alignment-preserved", release["sign"])
        self.assertIn("--v1-signing-enabled false", release["sign"])
        self.assertIn("--v2-signing-enabled true", release["sign"])
        self.assertIn("--require-hashes", release["verify"])
        self.assertIn("scripts/release/apksigcopier-requirements.txt", release["verify"])
        self.assertIn('apksigcopier" compare', release["verify"])
        self.assertNotIn("apt-get", release["verify"])

    def test_both_apks_use_the_reproducible_signing_flags_and_verifier(self):
        release = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            binary = root / "bin"
            binary.mkdir()
            calls = root / "calls"
            fake = '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\n[[ -z "$FAIL" || "$*" != *"$FAIL"* ]]\n'
            for name in ("apksigner", "apksigcopier"):
                path = binary / name
                path.write_text(fake, encoding="utf-8")
                path.chmod(0o755)
            env = dict(os.environ, TAG="v1.2.3", CALLS=str(calls), FAIL="", apksigner=str(binary / "apksigner"),
                       verifier=str(root), keystore="key", RELEASE_KEY_ALIAS="alias")

            def loop(job):
                start = job.index("          for flavor in play foss; do")
                end = job.index("\n          done", start) + len("\n          done")
                return job[start:end]

            sign = loop(release["sign"])
            verify = loop(release["verify"])
            for script, paths in (
                (sign, ("apk/play/release/app-play-release-unsigned.apk", "apk/foss/release/app-foss-release-unsigned.apk")),
                (verify, ("app/build/outputs/apk/play/release/app-play-release-unsigned.apk", "app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk")),
            ):
                calls.write_text("")
                run = subprocess.run(["bash", "-e", "-c", script], env=env, capture_output=True, text=True, check=False)
                self.assertEqual(0, run.returncode, run.stderr)
                lines = calls.read_text().splitlines()
                self.assertEqual(2, len(lines))
                for line, path, output in zip(lines, paths, ("Crosstune-v1.2.3.apk", "Crosstune-v1.2.3-foss.apk")):
                    self.assertIn(path, line)
                    self.assertIn(output, line)
                    if script == sign:
                        for flag in ("-J-enable-native-access=ALL-UNNAMED", "--alignment-preserved", "--v1-signing-enabled false", "--v2-signing-enabled true"):
                            self.assertIn(flag, line)
                for path in paths:
                    calls.write_text("")
                    failed = subprocess.run(["bash", "-e", "-c", script], env={**env, "FAIL": path}, capture_output=True, text=True, check=False)
                    self.assertNotEqual(0, failed.returncode, path)

    def test_the_play_key_is_in_the_three_play_jobs_only(self):
        found = sorted((workflow, job) for workflow, job, _ in play_jobs())
        self.assertEqual(
            [("promote.yml", "production"), ("release.yml", "play"), ("store-listing.yml", "listing")], found)

    def test_only_the_play_jobs_read_a_play_key(self):
        for path in WORKFLOWS.glob("*.yml"):
            for name, job in jobs(path.read_text(encoding="utf-8")).items():
                if PLAY_KEY not in job:
                    with self.subTest(workflow=path.name, job=name):
                        self.assertNotRegex(job, r"secrets\.PLAY_|\$\{?PLAY_KEY_JSON")

    def test_every_play_job_skips_play_with_a_notice_when_the_key_isnt_set(self):
        for workflow, name, job in play_jobs():
            with self.subTest(workflow=workflow, job=name):
                self.assertIn("PLAY_KEY_JSON: ${{ " + PLAY_KEY + " }}", job)
                script = job.split("run: |", 1)[1] if "PLAY_KEY_JSON" in job.split("run: |", 1)[0] else job
                skip = re.search(r'if \[\[ -z "\$PLAY_KEY_JSON" \]\]; then\n\s+echo "::notice::([^"]+)"\n\s+exit 0', script)
                self.assertIsNotNone(skip, "no notice and exit 0 when PLAY_KEY_JSON isn't set")
                self.assertIn("PLAY_SERVICE_ACCOUNT_JSON isn't set", skip.group(1))

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

    def test_the_play_jobs_install_fastlane_before_the_key_is_there(self):
        for workflow, name, job in play_jobs():
            with self.subTest(workflow=workflow, job=name):
                steps = job.split("\n      - ")
                setup = [i for i, step in enumerate(steps) if re.search(r"\.github/play/play\.sh setup$", step, re.MULTILINE)]
                keyed = [i for i, step in enumerate(steps) if PLAY_KEY in step]
                self.assertEqual(1, len(setup), job)
                self.assertNotIn("secrets.", steps[setup[0]])
                self.assertLess(setup[0], min(keyed))

    def test_ci_required_waits_on_the_play_job(self):
        ci = jobs((WORKFLOWS / "ci.yml").read_text(encoding="utf-8"))
        self.assertIn("play", ci)
        needs = re.search(r"\n    needs:\n((?:      - \S+\n)+)", ci["required-ci"]).group(1).split()
        self.assertIn("play", [n for n in needs if n != "-"])

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
        self.assertEqual(["workflow_dispatch:"], re.findall(r"^  (\S+:)$", triggers, re.MULTILINE))
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


# A gh that keeps the arguments of `gh release create`, each ended by a NUL, as the notes have lines. It lists the
# releases with FAKE_RELEASES (lines of tag, draft and pre-release, with tabs between), or fails with
# FAKE_GH_FAILS, as on an outage.
FAKE_GH = r"""#!/usr/bin/env bash
if [[ "$1 $2" == "release create" ]]; then
  printf '%s\0' "$@" > "$FAKE_GH_CREATE"
elif [[ "$*" == "api --paginate repos/$GITHUB_REPOSITORY/releases --jq "* ]]; then
  if [[ -n "${FAKE_GH_FAILS:-}" ]]; then
    echo "gh: Server Error (HTTP 500)" >&2
    exit 1
  fi
  printf '%b' "$FAKE_RELEASES"
else
  echo "fake gh doesn't know: $*" >&2
  exit 1
fi
"""


def releases(*tags, draft=(), pre=()):
    return "".join(f"{tag}\t{str(tag in draft).lower()}\t{str(tag in pre).lower()}\n" for tag in tags)


AAB = b"the signed App Bundle"
AAB_SHA256 = hashlib.sha256(AAB).hexdigest()


class PublishTest(unittest.TestCase):
    def publish(self, tag, out="", fails=False, aab=AAB, digest=AAB_SHA256):
        with tempfile.TemporaryDirectory() as work:
            if aab is not None:
                Path(work, f"Crosstune-{tag}.aab").write_bytes(aab)
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
                "AAB_SHA256": digest,
                "FAKE_GH_CREATE": str(create),
                "FAKE_RELEASES": out,
                "FAKE_GH_FAILS": "1" if fails else "",
            }
            # As GitHub runs a step: bash -e, with no pipefail.
            run = subprocess.run(["bash", "-e", "-c", publish_step()], env=env, cwd=work,
                                 capture_output=True, text=True, check=False)
            sent = create.read_text(encoding="utf-8").split("\0")[:-1] if create.exists() else None
            return run, sent

    def flags(self, sent):
        """--latest and --prerelease in any form, and --notes-start-tag with its tag, as sent."""
        found = [arg for arg in sent if arg.startswith(("--latest", "--prerelease"))]
        if "--notes-start-tag" in sent:
            found += ["--notes-start-tag", sent[sent.index("--notes-start-tag") + 1]]
        return found

    def assert_flags(self, tag, out, expected):
        run, sent = self.publish(tag, out)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(expected, self.flags(sent))

    def test_a_newer_version_becomes_the_latest_release_and_its_notes_start_at_the_one_before(self):
        for tag, out, start in (("v2.28.0", releases("v2.26.0", "v2.27.0"), "v2.27.0"),
                                ("v2.10.0", releases("v2.9.0", "v2.8.0"), "v2.9.0"),
                                ("v3.0.0", releases("v2.99.99"), "v2.99.99")):
            with self.subTest(tag=tag):
                self.assert_flags(tag, out, ["--notes-start-tag", start])

    def test_the_first_release_has_no_start(self):
        self.assert_flags("v1.0.0", "", [])

    def test_a_final_after_its_test_versions_is_the_latest_and_its_notes_start_at_the_last_final(self):
        out = releases("v2.27.0-rc.2", "v2.27.0-rc.1", "v2.26.0", pre=("v2.27.0-rc.2",))
        # v2.27.0-rc.1 was made before pre-releases were marked: it still never counts as newer, or as a start.
        self.assert_flags("v2.27.0", out, ["--notes-start-tag", "v2.26.0"])

    def test_a_pre_release_is_never_the_latest_release(self):
        self.assert_flags("v2.28.0-rc.1", releases("v2.27.0", "v2.28.0-rc.0", pre=("v2.28.0-rc.0",)),
                          ["--prerelease", "--notes-start-tag", "v2.27.0"])

    def test_a_draft_counts_for_nothing(self):
        self.assert_flags("v2.28.0", releases("v2.30.0", "v2.27.0", draft=("v2.30.0",)), ["--notes-start-tag", "v2.27.0"])

    def test_the_release_keeps_the_signed_app_bundle_for_play_console(self):
        run, sent = self.publish("v2.26.0", releases("v2.25.0"))
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(
            ["release", "create", "v2.26.0", "Crosstune-v2.26.0.apk", "Crosstune-v2.26.0-foss.apk", "Crosstune-v2.26.0.aab"],
            sent[:6],
        )
        publish = jobs((WORKFLOWS / "release.yml").read_text(encoding="utf-8"))["publish"]
        self.assertIn("name: Crosstune-${{ env.TAG }}-play", publish)

    def notes(self, sent):
        return sent[sent.index("--notes") + 1]

    def test_the_notes_have_the_signing_certificate_and_the_app_bundles_digest(self):
        run, sent = self.publish("v2.26.0", releases("v2.25.0"))
        self.assertEqual(0, run.returncode, run.stderr)
        # Promote reads this line back from the notes, and compares it with the bundle Play has.
        lines = self.notes(sent).split("\n")
        self.assertIn("Signing certificate SHA-256: `00`", lines)
        self.assertIn(f"App Bundle SHA-256: `{AAB_SHA256}`", lines)

    def test_the_notes_line_is_the_one_promote_reads(self):
        run, sent = self.publish("v2.26.0")
        self.assertEqual(0, run.returncode, run.stderr)
        play_rb = (ROOT / ".github/play/play.rb").read_text(encoding="utf-8")
        self.assertIn('DIGEST_LINE = "App Bundle SHA-256"', play_rb)
        self.assertIn("DIGEST_FORMAT = /\\A#{DIGEST_LINE}: `([0-9A-Fa-f]{64})`\\z/", play_rb)
        self.assertRegex(self.notes(sent).split("\n")[2], r"\AApp Bundle SHA-256: `[0-9a-f]{64}`\Z")

    def test_no_release_is_made_when_the_app_bundle_isnt_the_one_that_was_signed(self):
        # The sign job's digest and the file the publish job downloaded have to agree: a swapped file stops it.
        for name, kwargs in (("another bundle", {"aab": b"another App Bundle"}),
                             ("no bundle", {"aab": None}),
                             ("no digest", {"digest": ""}),
                             ("a short digest", {"digest": AAB_SHA256[:63]}),
                             ("a long digest", {"digest": AAB_SHA256 + "0"}),
                             ("a digest that isn't hex", {"digest": "g" * 64}),
                             ("a digest in capitals", {"digest": AAB_SHA256.upper()})):
            with self.subTest(name):
                run, sent = self.publish("v2.26.0", releases("v2.25.0"), **kwargs)
                self.assertNotEqual(0, run.returncode)
                self.assertIsNone(sent)
                self.assertIn("::error::", run.stderr + run.stdout)

    def test_the_sign_job_hands_on_the_digest_of_the_bundle_it_signed(self):
        text = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        steps = jobs(text)
        self.assertIn("aab_sha256: ${{ steps.sign.outputs.aab_sha256 }}", steps["sign"])
        self.assertIn("AAB_SHA256: ${{ needs.sign.outputs.aab_sha256 }}", steps["publish"])
        # Read from the signed file itself, after the verify, and written as an output.
        sign = steps["sign"]
        self.assertLess(sign.index("jarsigner -verify"), sign.index('sha256sum "Crosstune-$TAG.aab"'))
        self.assertIn('echo "aab_sha256=$aab_sha256" >> "$GITHUB_OUTPUT"', sign)

    def test_promote_never_downloads_a_bundle(self):
        # Production takes only a release already on the first testing track, from Play itself. It reads the
        # release's notes from GitHub, never the bundle, and compares the digest there with the bundle Play has.
        play_sh = (ROOT / ".github/play/play.sh").read_text(encoding="utf-8")
        self.assertNotIn("releases/download", play_sh)
        self.assertEqual(1, play_sh.count("curl "))
        self.assertIn("releases/tags/$tag", play_sh)
        self.assertNotIn("--aab", (ROOT / ".github/play/play.rb").read_text(encoding="utf-8"))

    def test_promote_reads_the_release_notes_with_a_token_that_can_do_nothing(self):
        text = (WORKFLOWS / "promote.yml").read_text(encoding="utf-8")
        job = jobs(text)["production"]
        self.assertIn("permissions: {}", job)
        self.assertIn("GH_TOKEN: ${{ github.token }}", job)

    def test_no_release_is_made_when_github_cant_list_the_releases(self):
        run, sent = self.publish("v2.26.1", fails=True)
        self.assertNotEqual(0, run.returncode)
        self.assertIsNone(sent)


if __name__ == "__main__":
    unittest.main()
