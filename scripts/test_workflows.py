"""Checks on the GitHub workflows that actionlint doesn't make.

Each checkout leaves the job's token out of .git/config, so the code and pages a job runs or opens can't
read it there. Only a job that pushes with git keeps it, and it's named below with what it pushes.

Each script's tests run in a workflow, so a script that makes what the app's tests read is held to its own.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

# (file, job): what it pushes with git.
KEEPS_TOKEN = {
    ("jmdict.yml", "propose"): "the branch of the pull request it proposes",
    ("extension-release.yml", "release"): "the release's tag",
}

JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
# A checkout, as a step's first key ("- uses: …") or after its name ("- name: …", then "uses: …").
CHECKOUT = re.compile(r"^(\s*)(- )?uses: actions/checkout@")
STEP = re.compile(r"^(\s*)- ")


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
        checkout = CHECKOUT.match(line)
        if not checkout:
            continue
        # The step's own keys sit two past its dash, before and after the checkout's line.
        inside = len(checkout.group(1)) + (2 if checkout.group(2) else 0)
        start = number - 1
        while not checkout.group(2) and start > 0:
            step = STEP.match(lines[start])
            if step and len(step.group(1)) + 2 == inside:
                break
            start -= 1
        block = [lines[start].strip().removeprefix("- ")]
        for after in lines[start + 1:]:
            if after.strip() and len(after) - len(after.lstrip()) < inside:
                break
            block.append(after.strip())
        if "persist-credentials: false" not in block:
            found.append((job, number))
    return found


def unrun(tests, workflows):
    """Each of [tests], paths from the repo's root, that no `unittest` line in [workflows]' text runs, alone or by
    `discover -s` of its folder."""
    run = set(re.findall(r"python3 -m unittest (\S+\.py)", workflows))
    folders = set(re.findall(r"python3 -m unittest discover -s (\S+) -p \"test_\*\.py\"", workflows))
    return [test for test in tests if test not in run and test.rsplit("/", 1)[0] not in folders]


class WorkflowsTest(unittest.TestCase):
    def test_each_scripts_tests_run_in_a_workflow(self):
        tests = sorted(str(path.relative_to(ROOT)) for path in (ROOT / "scripts").rglob("test_*.py"))
        self.assertEqual([], unrun(tests, "\n".join(path.read_text(encoding="utf-8") for path in WORKFLOWS.glob("*.yml"))))

    def test_a_test_runs_alone_or_with_its_folder(self):
        text = 'python3 -m unittest discover -s scripts/a -p "test_*.py"\npython3 -m unittest scripts/test_b.py\n'
        tests = ["scripts/a/test_x.py", "scripts/test_b.py", "scripts/c/test_y.py", "scripts/a/d/test_z.py"]
        self.assertEqual(["scripts/c/test_y.py", "scripts/a/d/test_z.py"], unrun(tests, text))

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
            "  named:",
            "    steps:",
            "      - name: Check out",
            "        uses: actions/checkout@abc # v1",
            "      - name: Check out without the token",
            "        with:",
            "          persist-credentials: false",
            "        uses: actions/checkout@abc # v1",
            "      - run: true",
        ])
        self.assertEqual([("plain", 6), ("other", 17), ("named", 23)], checkouts_with_token(text))


if __name__ == "__main__":
    unittest.main()
