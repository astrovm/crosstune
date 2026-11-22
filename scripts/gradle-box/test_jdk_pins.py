"""Every place that names a JDK names the same major version as the box, so a local build runs what CI runs."""
import json
import re
import unittest
from collections import Counter
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
IMAGE = re.compile(r"eclipse-temurin:(\d+)[^\s\"'`]*")
SETUP = re.compile(r"java-version:\s*[\"']?([^\s\"'#]+)")
DOCS = re.compile(r"CI uses JDK (\d+)")


def box_major() -> str:
    found = IMAGE.search((ROOT / "scripts/gradle-box/Containerfile").read_text())
    assert found, "the box's Containerfile names no eclipse-temurin image"
    return found.group(1)


def files() -> list[Path]:
    workflows = ROOT / ".github/workflows"
    named = [*workflows.glob("*.yml"), *workflows.glob("*.yaml"), ROOT / "CONTRIBUTING.md"]
    # The composite actions the workflows set the JDK up with.
    named += [*(ROOT / ".github/actions").rglob("action.yml"), *(ROOT / ".github/actions").rglob("action.yaml")]
    scripts = ROOT / "scripts"
    named += [p for p in scripts.rglob("*") if p.is_file() and (p.suffix in (".py", ".sh") or p.name.startswith("Containerfile"))]
    return sorted(set(named) - {Path(__file__).resolve()})


def pins() -> list[tuple[str, str]]:
    found = []
    for path in files():
        text = path.read_text()
        name = str(path.relative_to(ROOT))
        found += [(name, v) for v in IMAGE.findall(text)]
        found += [(name, v) for v in SETUP.findall(text)]
        found += [(name, v) for v in DOCS.findall(text)]
        if "java-version-file" in text:
            found.append((name, "java-version-file"))
    return found


class JdkPinsTest(unittest.TestCase):
    def test_every_jdk_is_the_boxs(self):
        major = box_major()
        found = pins()
        self.assertTrue(any(name.endswith((".yml", ".yaml")) for name, _ in found), "no setup-java step found")
        self.assertIn((".github/actions/jdk/action.yml", major), found)
        # A pin that isn't a plain major (a matrix, a file, a full version) can't be held equal, so it fails too.
        self.assertEqual([], [(name, v) for name, v in found if v != major])

    def test_renovate_moves_every_pin_to_the_next_long_term_jdk_together(self):
        # The rules live in astrovm/repo-settings (config/renovate/astrovm/crosstune.json), which writes this file:
        # a sync without them fails here.
        config = json.loads((ROOT / ".github/renovate.json").read_text())
        allowed = [
            re.compile(rule["allowedVersions"].strip("/"))
            for rule in config.get("packageRules", [])
            if "docker.io/library/eclipse-temurin" in rule.get("matchPackageNames", [])
            and "allowedVersions" in rule
        ]
        self.assertEqual(1, len(allowed), "no Renovate rule holds eclipse-temurin to long-term JDKs")
        # Any other condition (a manager, a file, an update type) leaves some pins out of the rule.
        rule = next(r for r in config["packageRules"] if "docker.io/library/eclipse-temurin" in r.get("matchPackageNames", []))
        self.assertEqual({"matchDatasources": ["docker"]}, {k: v for k, v in rule.items() if k.startswith("match") and k != "matchPackageNames"})
        # A long-term JDK every two years from 25; the ones between lose their updates in six months.
        for major in range(25, 50):
            for tag in (str(major), f"{major}-jdk"):
                self.assertEqual((major - 25) % 4 == 0, bool(allowed[0].fullmatch(tag)), tag)
        # Renovate reads the box's Containerfile itself; every other pin needs a custom manager that reads it as this
        # image, or an update moves the box alone and the test above fails.
        read = Counter()
        for manager in config.get("customManagers", []):
            if (manager.get("depNameTemplate"), manager.get("datasourceTemplate")) != ("docker.io/library/eclipse-temurin", "docker"):
                continue
            self.assertEqual("regex", manager.get("customType"))
            # "any" reads each match on its own; the other strategies join them into one.
            self.assertEqual("any", manager.get("matchStringsStrategy", "any"))
            # Renovate's patterns are JavaScript's: a named group is (?<name>...).
            strings = [re.compile(s.replace("(?<", "(?P<")) for s in manager.get("matchStrings", [])]
            for path in files():
                name = str(path.relative_to(ROOT))
                if any(covers(pattern, name) for pattern in manager.get("managerFilePatterns", [])):
                    # Each pin is counted, so a file with two pins needs both read.
                    read.update((name, m.group("currentValue").split("-")[0]) for s in strings for m in s.finditer(path.read_text()))
        left = Counter((name, v) for name, v in pins() if name != "scripts/gradle-box/Containerfile")
        self.assertEqual(Counter(), left - read, "JDK pins no Renovate manager reads")


def covers(pattern: str, name: str) -> bool:
    """Renovate's managerFilePatterns: a regex between slashes, a glob otherwise."""
    if len(pattern) > 1 and pattern.startswith("/") and pattern.endswith("/"):
        return re.search(pattern[1:-1], name) is not None
    return PurePosixPath(name).full_match(pattern)


if __name__ == "__main__":
    unittest.main()
