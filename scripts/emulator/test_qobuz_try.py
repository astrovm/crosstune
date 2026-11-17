"""Benchmark setup must succeed before a playback request can start."""

import runpy
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock


class QobuzSetupTest(unittest.TestCase):
    def test_a_failed_preferences_write_stops_before_playback(self):
        calls = []
        failed = False

        def run(command, **kwargs):
            nonlocal failed
            calls.append(command)
            if command == ["adb", "devices"]:
                return subprocess.CompletedProcess(command, 0, "fixture-device\tdevice\n", "")
            if "run-as" in command:
                failed = True
                if kwargs.get("check"):
                    raise subprocess.CalledProcessError(1, command, stderr="fixture write failed")
                return subprocess.CompletedProcess(command, 1, "", "fixture write failed")
            if failed:
                self.fail("The benchmark continued after its preferences write failed")
            return subprocess.CompletedProcess(command, 0, "", "")

        args = ["qobuz_try.py", "fixture-device", "https://example.invalid/fixture", "Fixture Song"]
        with mock.patch.object(sys, "argv", args), mock.patch.object(subprocess, "run", side_effect=run):
            with self.assertRaises(subprocess.CalledProcessError):
                runpy.run_path(str(Path(__file__).with_name("qobuz_try.py")), run_name="__main__")
        self.assertIn("run-as", calls[-1])


if __name__ == "__main__":
    unittest.main()
