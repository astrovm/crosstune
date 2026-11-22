"""second_song.py takes the shared device through devicelock.py, in a cache of its own."""
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import second_song

DEVICELOCK = Path(__file__).resolve().parents[1] / "devicelock.py"


SAMSUNG = "R5CT1234ABC          device usb:1-1 product:b0qxxx model:SM_S908E device:b0q transport_id:1"
OTHER = "R5CT9999XYZ          device usb:1-2 product:a52 model:SM_A525F device:a52q transport_id:2"


def listing(*rows):
    return "List of devices attached\n" + "".join(row + "\n" for row in rows)


class SharedDeviceTest(unittest.TestCase):
    def test_the_samsung_found_by_its_model_or_passed_by_its_serial(self):
        self.assertEqual("samsung", second_song.shared_device("R5CT1234ABC", listing(SAMSUNG, OTHER)))

    def test_a_samsung_passed_by_its_serial_next_to_another_of_its_model(self):
        # samsung_serial() refuses two, but the one passed with --serial is still the Samsung's turn.
        twin = SAMSUNG.replace("R5CT1234ABC", "R5CT5678DEF")
        self.assertEqual("samsung", second_song.shared_device("R5CT5678DEF", listing(SAMSUNG, twin)))

    def test_a_test_emulator_by_its_port(self):
        for name, spec in second_song.EMULATORS.items():
            serial = f"emulator-{spec['port']}"
            self.assertEqual(name, second_song.shared_device(serial, listing(SAMSUNG, f"{serial}  device product:sdk_gphone64 model:sdk_gphone64_x86_64")))
            self.assertEqual(name, second_song.shared_device(serial, listing()))

    def test_no_other_device(self):
        self.assertIsNone(second_song.shared_device("emulator-5554", listing(SAMSUNG, "emulator-5554  device model:sdk_gphone64_x86_64")))
        self.assertIsNone(second_song.shared_device("R5CT9999XYZ", listing(SAMSUNG, OTHER)))
        self.assertIsNone(second_song.shared_device("R5CT9999XYZ", listing(OTHER)))


class DeadlineTest(unittest.TestCase):
    def setUp(self):
        self.addCleanup(setattr, second_song, "deadline", None)

    def test_a_command_runs_out_of_a_turn(self):
        self.assertEqual("out\n", second_song.run(["echo", "out"]))

    def test_a_command_runs_while_it_can_end_before_the_turn_does(self):
        second_song.deadline = time.monotonic() + second_song.COMMAND_SECONDS + 30
        self.assertEqual("in\n", second_song.run(["echo", "in"]))

    def test_no_command_starts_that_could_outlast_the_turn(self):
        second_song.deadline = time.monotonic() + second_song.COMMAND_SECONDS - 1
        with self.assertRaises(TimeoutError):
            second_song.run(["echo", "late"])


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
        with second_song.turn(None, "second song test") as restoring:
            self.assertEqual("samsung: free", self.lock("who", "samsung").strip())
            restoring()
            self.assertIsNone(second_song.deadline)

    def test_a_turn_stops_commands_in_time_to_put_the_app_back_before_the_lock_lets_go(self):
        took = time.monotonic()
        with second_song.turn("samsung", "second song test") as restoring:
            working = second_song.deadline
            restoring()
            putting_back = second_song.deadline
        self.assertIsNone(second_song.deadline)
        self.assertEqual(second_song.RESTORE_SECONDS, putting_back - working)
        lease = second_song.PAIR_MINUTES * 60
        # The holder's own end: its turn started after take was called, and before it returned.
        self.assertLessEqual(took + lease, putting_back)
        self.assertLessEqual(putting_back, time.monotonic() + lease)
        self.assertEqual("samsung: free", self.lock("who", "samsung").strip())

    def test_the_turn_ends_when_the_holders_note_says_not_when_take_returns(self):
        # On a busy machine take can return a while after the holder's clock started: the note's end is the one kept.
        took = time.monotonic()
        pid = self.lock("take", "samsung", "--who", "late turn", "--minutes", "1").strip()
        self.assertLessEqual(took + 60, second_song.lease_end(pid))
        self.assertLessEqual(second_song.lease_end(pid), time.monotonic() + 60)
        with self.assertRaises(RuntimeError):
            second_song.lease_end("999999999")

    def test_a_turn_already_over_leaves_the_next_agents_turn_alone(self):
        with second_song.turn("samsung", "second song test"):
            # The holder let go at the end of its minutes, and another agent in this same session took the device after.
            self.lock("release", "samsung", "--who", "second song test")
            self.lock("take", "samsung", "--who", "another agent", "--minutes", "1")
        self.assertIsNone(second_song.deadline)
        self.assertIn("held by another agent", self.lock("who", "samsung"))

    def test_a_turn_whose_end_cant_be_read_lets_the_device_go(self):
        with mock.patch.object(second_song, "lease_end", side_effect=RuntimeError("no end")):
            with self.assertRaises(RuntimeError), second_song.turn("samsung", "second song test"):
                self.fail("the pair ran without knowing when its turn ends")
        self.assertIsNone(second_song.deadline)
        self.assertEqual("samsung: free", self.lock("who", "samsung").strip())


if __name__ == "__main__":
    unittest.main()
