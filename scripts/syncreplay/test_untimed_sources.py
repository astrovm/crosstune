import importlib.util
import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "readingsaudit"))

from source_calls import called, declared  # noqa: E402  the readings guard's parser, from its own folder

os.environ.pop("SYNCREPLAY_DIR", None)
spec = importlib.util.spec_from_file_location("replay_sources", Path(__file__).with_name("syncreplay.py"))
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)


class UntimedSourcesTest(unittest.TestCase):
    def test_the_untimed_replay_compiles_every_file_it_reaches(self):
        harness = "scripts/syncreplay/UntimedReplay.kt"
        reached = called(ROOT, harness, declared(ROOT)) | {harness}
        self.assertEqual(set(), reached - set(replay.UNTIMED_SOURCES))

    def test_the_untimed_replay_compiles_nothing_it_does_not_reach(self):
        harness = "scripts/syncreplay/UntimedReplay.kt"
        reached = called(ROOT, harness, declared(ROOT)) | {harness}
        self.assertEqual(set(), set(replay.UNTIMED_SOURCES) - reached)


if __name__ == "__main__":
    unittest.main()
