"""A fix to an older version, a hotfix: v2.26.1 after v2.27.0. See step 6 of "Releasing" in CONTRIBUTING.md.

    python3 scripts/release/hotfix.py start v2.26.0 2.26.1
        Makes the branch hotfix/v2.26.1 from the tag v2.26.0, with main's release workflow, Google Play tools
        and this script, and the new version in app/build.gradle.kts, in one commit.
    python3 scripts/release/hotfix.py check
        Says what the branch still needs before its tag, or gives the commands that push the branch and tag.

A tag runs the release workflow of its own commit, and one from before .github/play sends the App Bundle
straight to production, and cancels a Google Play review in progress. So the branch takes main's. promote.yml
runs from main and reads the release notes there, so they go on main too.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
GRADLE = "app/build.gradle.kts"
NOTES = "fastlane/metadata/android/en-US/changelogs"
# What a tag runs, and what promote.yml runs from main: the same on both. This script too, for its check, as
# an old tag has none.
TOOLING = (".github/workflows/release.yml", ".github/play", "scripts/release/hotfix.py")
TAKE_MAINS = "git checkout origin/main -- " + " ".join(TOOLING)


class Stop(Exception):
    pass


def git(root, *args, check=True):
    run = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=False)
    if check and run.returncode != 0:
        raise Stop(f"git {' '.join(args)}: {run.stderr.strip()}")
    return run


def version(text):
    match = re.fullmatch(r"v?([0-9]+)\.([0-9]+)\.([0-9]+)", text)
    if not match:
        raise Stop(f"'{text}' must look like 2.26.1")
    return tuple(int(part) for part in match.groups())


def code(parts):
    # The same as release.yml.
    return parts[0] * 1000000 + parts[1] * 10000 + parts[2] * 100


def clean(root):
    if git(root, "status", "--porcelain").stdout.strip():
        raise Stop("This checkout has changes: commit them, or set them aside, first")


def fetch(root):
    git(root, "fetch", "--quiet", "--tags", "origin", "+refs/heads/main:refs/remotes/origin/main")


def start(root, base, new):
    parts = version(new)
    clean(root)
    fetch(root)
    refs = git(root, "ls-remote", "--tags", "origin").stdout.split()
    tags = {ref.removeprefix("refs/tags/") for ref in refs if ref.startswith("refs/tags/")}
    tag = "v{}.{}.{}".format(*parts)
    if base not in tags:
        raise Stop(f"{base} isn't a tag on origin")
    if parts <= version(base):
        raise Stop(f"{new} isn't above {base}")
    if tag in tags:
        raise Stop(f"{tag} is a tag already")

    branch = f"hotfix/{tag}"
    git(root, "switch", "--quiet", "--create", branch, base)
    git(root, "rm", "-r", "--quiet", "--ignore-unmatch", TOOLING[1])
    git(root, "checkout", "origin/main", "--", *TOOLING)
    gradle = root / GRADLE
    text = gradle.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^(\s*versionCode\s*=\s*)[0-9]+", rf"\g<1>{code(parts)}", text)
    text = re.sub(r'(?m)^(\s*versionName\s*=\s*)"[^"]*"', rf'\g<1>"{tag[1:]}"', text)
    gradle.write_text(text, encoding="utf-8")
    git(root, "add", GRADLE)
    git(root, "commit", "--quiet", "--message",
        f"chore(release): start {tag[1:]} from {base} with main's release workflow")
    print(f"On {branch}, from {base}, with main's release workflow and version {tag[1:]}.")
    print(f"1. Fix it here, and add the release notes as {NOTES}/{code(parts)}.txt.")
    print("2. Add the same notes to main in a pull request: promote.yml reads them there.")
    print("3. Run python3 scripts/release/hotfix.py check, which gives the commands for the tag.")
    return 0


def check(root):
    clean(root)
    fetch(root)
    gradle = git(root, "show", f"HEAD:{GRADLE}").stdout
    name = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]*)"', gradle).group(1)
    notes = f"{NOTES}/{code(version(name))}.txt"
    problems = []
    for path in TOOLING:
        if git(root, "rev-parse", f"HEAD:{path}", check=False).stdout != \
                git(root, "rev-parse", f"origin/main:{path}", check=False).stdout:
            problems.append(f"{path} isn't main's. Take it with {TAKE_MAINS}")
    for ref, where in (("HEAD", "this branch"), ("origin/main", "origin's main")):
        if git(root, "cat-file", "-e", f"{ref}:{notes}", check=False).returncode != 0:
            problems.append(f"{notes} isn't on {where}")
    for problem in problems:
        print(f"::error::{problem}", file=sys.stderr)
    if problems:
        return 1
    branch = git(root, "branch", "--show-current").stdout.strip()
    print("Ready. The release workflow builds the tag, and sends Google Play nothing. Push the branch and tag:")
    print(f"git tag v{name} && git push origin {branch} v{name}")
    print(f"Then run Actions → Promote to production with {name}.")
    return 0


def main(argv, root=ROOT):
    try:
        if len(argv) == 3 and argv[0] == "start":
            return start(root, argv[1], argv[2])
        if argv == ["check"]:
            return check(root)
        raise Stop("usage: hotfix.py start TAG VERSION | hotfix.py check")
    except Stop as stop:
        print(f"::error::{stop}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
