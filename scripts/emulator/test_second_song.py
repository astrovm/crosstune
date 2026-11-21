"""second_song.py takes the shared device through devicelock.py, in a cache of its own."""
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import second_song

DEVICELOCK = Path(__file__).resolve().parents[1] / "devicelock.py"


class SharedDeviceTest(unittest.TestCase):
    def test_the_samsung_with_no_serial(self):
        self.assertEqual("samsung", second_song.shared_device(None))

    def test_a_test_emulator_by_its_port(self):
        for name, spec in second_song.EMULATORS.items():
            self.assertEqual(name, second_song.shared_device(f"emulator-{spec['port']}"))

    def test_no_other_device(self):
        self.assertIsNone(second_song.shared_device("emulator-5554"))
        self.assertIsNone(second_song.shared_device("R5CT1234ABC"))


class TurnTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        cache = Path(tmp.name)
        was = os.environ.get("CROSSTUNE_AGENT_CACHE")
        os.environ["CROSSTUNE_AGENT_CACHE"] = str(cache)
        self.addCleanup(lambda: os.environ.__setitem__("CROSSTUNE_AGENT_CACHE", was) if was else os.environ.pop("CROSSTUNE_AGENT_CACHE", None))
        self.addCleanup(lambda: self.lock("release", "samsung", "--force"))

    def lock(self, *args):
        return subprocess.run([sys.executable, str(DEVICELOCK), *args], capture_output=True, text=True, timeout=30, check=False).stdout

    def wait_for(self, condition):
        deadline = time.monotonic() + 15
        while not condition():
            self.assertLess(time.monotonic(), deadline, self.lock("who", "samsung"))
            time.sleep(0.05)

    def test_a_pair_waits_in_line_for_another_agents_turn_and_lets_go_after(self):
        self.lock("take", "samsung", "--who", "another agent", "--minutes", "1")
        inside = threading.Event()
        leave = threading.Event()

        def pair():
            with second_song.turn("samsung", "second song test"):
                inside.set()
                leave.wait(15)

        thread = threading.Thread(target=pair)
        thread.start()
        try:
            # In line behind the other agent, as devicelock.py who shows.
            self.wait_for(lambda: "waiting: second song test" in self.lock("who", "samsung"))
            self.assertFalse(inside.is_set())
            self.lock("release", "samsung", "--who", "another agent")
            self.assertTrue(inside.wait(15))
            self.assertIn("held by second song test", self.lock("who", "samsung"))
        finally:
            leave.set()
            thread.join(30)
        self.assertEqual("samsung: free", self.lock("who", "samsung").strip())

    def test_no_turn_is_taken_for_a_device_no_one_shares(self):
        with second_song.turn(None, "second song test"):
            self.assertEqual("samsung: free", self.lock("who", "samsung").strip())


if __name__ == "__main__":
    unittest.main()
