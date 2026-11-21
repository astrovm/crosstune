"""play.sh --help prints its header, which CONTRIBUTING.md points to for how the Play jobs check before they send."""
import subprocess
import unittest
from pathlib import Path

PLAY = Path(__file__).resolve().parents[2] / ".github/play/play.sh"


class PlayHelpTest(unittest.TestCase):
    def test_help_prints_the_header_alone(self):
        header = []
        for line in PLAY.read_text(encoding="utf-8").splitlines()[1:]:
            if not line.startswith("#"):
                break
            header.append(line.removeprefix("#").removeprefix(" "))
        for flag in ("--help", "-h"):
            with self.subTest(flag=flag):
                # No key, no gems and no network: help needs none of them.
                result = subprocess.run(["bash", str(PLAY), flag], capture_output=True, text=True, check=False, env={"PATH": "/usr/bin:/bin"})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("\n".join(header) + "\n", result.stdout)
                self.assertIn("play.sh production VERSION", result.stdout)
                self.assertNotIn("set -euo pipefail", result.stdout)


if __name__ == "__main__":
    unittest.main()
