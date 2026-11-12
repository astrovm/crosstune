"""Checks on the GitHub workflows that actionlint doesn't make.

Each checkout leaves the job's token out of .git/config, so the code and pages a job runs or opens can't
read it there. Only a job that pushes with git keeps it, and it's named below with what it pushes.
"""

import re
import unittest
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parent.parent / ".github" / "workflows"

# (file, job): what it pushes with git.
KEEPS_TOKEN = {
    ("jmdict.yml", "propose"): "the branch of the pull request it proposes",
    ("extension-release.yml", "release"): "the release's tag",
}

JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
CHECKOUT = re.compile(r"^(\s*)- uses: actions/checkout@")


def checkouts_with_token(text):
    """The (job, line) of each checkout in a workflow's text that keeps the token."""
    found = []
    job = None
    in_jobs = False
    lines = text.split("\n")
    for number, line in enumerate(lines, 1):
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not line.startswith(" ") and line.strip():
            in_jobs = False
        name = JOB.match(line)
        if in_jobs and name:
            job = name.group(1)
        step = CHECKOUT.match(line)
        if not step:
            continue
        inside = len(step.group(1)) + 2
        block = []
        for after in lines[number:]:
            if after.strip() and len(after) - len(after.lstrip()) < inside:
                break
            block.append(after.strip())
        if "persist-credentials: false" not in block:
            found.append((job, number))
    return found


class WorkflowsTest(unittest.TestCase):
    def test_only_a_job_that_pushes_keeps_its_token(self):
        kept = [
            f"{path.name}:{line} {job}"
            for path in sorted(WORKFLOWS.glob("*.yml"))
            for job, line in checkouts_with_token(path.read_text(encoding="utf-8"))
            if (path.name, job) not in KEEPS_TOKEN
        ]
        self.assertEqual([], kept)

    def test_each_job_named_here_still_keeps_it(self):
        for (name, job), pushes in KEEPS_TOKEN.items():
            with self.subTest(name=name):
                self.assertIn(job, [kept for kept, _ in checkouts_with_token((WORKFLOWS / name).read_text(encoding="utf-8"))], pushes)

    def test_a_checkout_is_read_with_what_it_is_given(self):
        text = "\n".join([
            "on:",
            "  push:",
            "jobs:",
            "  plain:",
            "    steps:",
            "      - uses: actions/checkout@abc # v1",
            "      - run: echo persist-credentials: false",
            "  given:",
            "    steps:",
            "      - uses: actions/checkout@abc # v1",
            "        with:",
            "          fetch-depth: 0",
            "          persist-credentials: false",
            "      - run: true",
            "  other:",
            "    steps:",
            "      - uses: actions/checkout@abc # v1",
            "        with:",
            "          persist-credentials: true",
        ])
        self.assertEqual([("plain", 6), ("other", 17)], checkouts_with_token(text))


if __name__ == "__main__":
    unittest.main()
