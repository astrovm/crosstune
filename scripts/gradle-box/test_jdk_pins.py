"""Every place that names a JDK names the same major version as the box, so a local build runs what CI runs."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = re.compile(r"eclipse-temurin:(\d+)[^\s\"'`]*")
SETUP = re.compile(r"java-version:\s*[\"']?([^\s\"'#]+)")
DOCS = re.compile(r"CI builds with JDK (\d+)")


def box_major() -> str:
    found = IMAGE.search((ROOT / "scripts/gradle-box/Containerfile").read_text())
    assert found, "the box's Containerfile names no eclipse-temurin image"
    return found.group(1)


def files() -> list[Path]:
    workflows = ROOT / ".github/workflows"
    named = [*workflows.glob("*.yml"), *workflows.glob("*.yaml"), ROOT / "CONTRIBUTING.md"]
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
        # A pin that isn't a plain major (a matrix, a file, a full version) can't be held equal, so it fails too.
        self.assertEqual([], [(name, v) for name, v in found if v != major])


if __name__ == "__main__":
    unittest.main()
