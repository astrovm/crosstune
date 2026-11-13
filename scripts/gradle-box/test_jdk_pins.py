"""Every place that names a JDK names the same major version as the box, so a local build runs what CI runs."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def box_major() -> str:
    text = (ROOT / "scripts/gradle-box/Containerfile").read_text()
    return re.search(r"eclipse-temurin:(\d+)-jdk", text).group(1)


def pins() -> list[tuple[str, str]]:
    found = []
    for workflow in sorted((ROOT / ".github/workflows").glob("*.yml")):
        for version in re.findall(r'java-version:\s*"?(\d+)"?', workflow.read_text()):
            found.append((workflow.name, version))
    for script in sorted((ROOT / "scripts").rglob("*.py")):
        for version in re.findall(r"eclipse-temurin:(\d+)-jdk", script.read_text()):
            found.append((str(script.relative_to(ROOT)), version))
    return found


class JdkPinsTest(unittest.TestCase):
    def test_every_jdk_is_the_boxs(self):
        major = box_major()
        found = pins()
        self.assertTrue(any(name.endswith(".yml") for name, _ in found), "no setup-java step found")
        self.assertEqual([], [(name, version) for name, version in found if version != major])


if __name__ == "__main__":
    unittest.main()
