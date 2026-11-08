"""Runs devicelock.py for real: two processes, a real flock, and a temp cache directory.

Nothing here opens the shared device locks under the agent cache.
"""
import fcntl
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "devicelock.py"
DEVICES = ("samsung", "crosstune-phone")


def pid_alive(pid):
    """True while pid is running. A zombie counts as gone: it no longer holds a lock."""
    if not isinstance(pid, int) or pid <= 0:
        return False
    stat = Path(f"/proc/{pid}/stat")
    if stat.exists():
        try:
            text = stat.read_text()
        except OSError:
            return False
        fields = text[text.rfind(")") + 2:].split()
        return bool(fields) and fields[0] != "Z"
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def descendants(pid):
    """Child processes of pid, from /proc, so cleanup reaches a detached holder."""
    found = []
    proc = Path("/proc")
    if not proc.is_dir():
        return found
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
        except OSError:
            continue
        end = stat.rfind(")")
        fields = stat[end + 2:].split()
        if len(fields) >= 2 and int(fields[1]) == pid:
            child = int(entry.name)
            found.append(child)
            found.extend(descendants(child))
    return found


class DeviceLockTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cache = Path(self.tmp.name) / "cache"
        self.cache.mkdir()
        self.procs = []
        self.pids = []
        self.addCleanup(self.stop_holders)

    def env(self, **extra):
        env = os.environ.copy()
        env["CROSSTUNE_AGENT_CACHE"] = str(self.cache)
        env["PYTHONUNBUFFERED"] = "1"
        env.pop("CROSSTUNE_LOCK_HOSTNAME", None)
        env.update(extra)
        return env

    def invoke(self, *args, env=None, timeout=15):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            capture_output=True, text=True, env=env or self.env(), timeout=timeout,
        )

    def invoke_ok(self, *args, timeout=15):
        result = self.invoke(*args, timeout=timeout)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        return result

    def spawn(self, *args):
        proc = subprocess.Popen(
            [sys.executable, str(SCRIPT), *args],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env(),
        )
        self.procs.append(proc)
        return proc

    def taken(self, device, who, minutes="2", timeout=15):
        result = self.invoke_ok("take", device, "--who", who, "--minutes", minutes, timeout=timeout)
        pid = int(result.stdout.strip().splitlines()[-1])
        self.pids.append(pid)
        self.assertTrue(pid_alive(pid), result.stderr)
        return pid

    def who(self, device=None, timeout=15):
        args = ["who"] if device is None else ["who", device]
        result = self.invoke_ok(*args, timeout=timeout)
        return result.stdout

    def note(self, device):
        directory = self.cache / "holders"
        self.assertTrue(directory.is_dir(), "the holder note was not written")
        matches = []
        for path in sorted(directory.glob("*.json")):
            data = json.loads(path.read_text())
            if data.get("device") == device:
                matches.append(data)
        self.assertEqual(len(matches), 1, matches)
        return matches[0]

    def wait_for(self, check, timeout=8):
        deadline = time.monotonic() + timeout
        last = ""
        while time.monotonic() < deadline:
            last = check()
            if last is True or (isinstance(last, str) and last):
                return last
            time.sleep(0.05)
        self.fail(f"timed out. last result: {last!r}\nwho:\n{self.who()}")

    def finish(self, proc, timeout=10):
        try:
            stdout, stderr = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, stderr = proc.communicate()
            self.fail(f"timed out\n{stdout}\n{stderr}")
        self.assertEqual(proc.returncode, 0, stderr)
        self.pids.append(int(stdout.strip().splitlines()[-1]))
        return self.pids[-1]

    def stop_holders(self):
        pids = set(self.pids)
        for proc in self.procs:
            if proc.poll() is None:
                pids.add(proc.pid)
            pids.update(descendants(proc.pid))
        for pid in list(pids):
            pids.update(descendants(pid))
        for pid in pids:
            try:
                os.kill(pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                pass
        deadline = time.monotonic() + 3
        for pid in pids:
            while pid_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.02)
            if pid_alive(pid):
                try:
                    os.kill(pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    pass
        for proc in self.procs:
            if proc.poll() is None:
                proc.kill()
            try:
                proc.communicate(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.communicate()

    def test_two_takers_queue_until_release_frees_the_next(self):
        first = self.taken("samsung", "ada")
        second = self.spawn("take", "samsung", "--who", "bea", "--minutes", "2")
        self.wait_for(lambda: "waiting: bea" in self.who("samsung"))
        self.assertIsNone(second.poll(), "the second taker should still be waiting")
        released = self.invoke_ok("release", "samsung")
        self.assertIn("released samsung", released.stdout)
        pid = self.finish(second)
        self.assertNotEqual(pid, first)
        status = self.who("samsung")
        self.assertIn(f"held by bea pid {pid}", status)
        self.assertNotIn("ada", status)
        self.assertNotIn("waiting:", status)

    def test_the_note_records_who_pid_start_and_until(self):
        pid = self.taken("samsung", "ada", minutes="15")
        note = self.note("samsung")
        self.assertEqual(note["who"], "ada")
        self.assertEqual(note["pid"], pid)
        start = datetime.fromisoformat(note["start"])
        until = datetime.fromisoformat(note["until"])
        self.assertEqual(until - start, timedelta(minutes=15))
        status = self.who("samsung")
        self.assertIn("held by ada", status)
        self.assertIn(f"pid {pid} (alive)", status)
        self.assertIn(note["start"], status)
        self.assertIn(note["until"], status)
        self.assertTrue((self.cache / "samsung.lock").is_file())
        self.assertNotIn("ada", (self.cache / "samsung.lock").read_text())

    def test_taking_a_lock_you_already_hold_returns_at_once(self):
        pid = self.taken("samsung", "ada")
        started = time.monotonic()
        again = self.invoke_ok("take", "samsung", "--who", "ada", "--minutes", "2")
        self.assertLess(time.monotonic() - started, 1)
        self.assertIn("already held", again.stderr)
        self.assertEqual(int(again.stdout.strip()), pid)
        self.assertEqual(self.note("samsung")["pid"], pid)
        self.assertNotIn("waiting:", self.who("samsung"))

    def test_a_dead_holder_leaves_the_device_free(self):
        pid = self.taken("samsung", "ada")
        os.kill(pid, signal.SIGKILL)
        self.wait_for(lambda: not pid_alive(pid) or "")
        status = self.who("samsung")
        self.assertIn("samsung: free", status)
        self.assertNotIn("ada", status)
        self.assertNotIn("stale", status)
        nxt = self.taken("samsung", "bea", timeout=5)
        self.assertNotEqual(nxt, pid)
        self.assertIn(f"held by bea pid {nxt} (alive)", self.who("samsung"))

    def test_minutes_expiring_frees_the_lock(self):
        pid = self.taken("samsung", "ada", minutes="0.05")
        self.assertIn(f"held by ada pid {pid} (alive)", self.who("samsung"))
        self.wait_for(
            lambda: "samsung: free" in self.who("samsung") and not pid_alive(pid), timeout=8,
        )
        self.taken("samsung", "bea", timeout=5)

    def test_release_refuses_someone_else_until_force(self):
        proc = subprocess.Popen(
            [sys.executable, str(SCRIPT), "take", "samsung", "--who", "ada", "--minutes", "2"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env(),
            start_new_session=True,
        )
        self.procs.append(proc)
        pid = self.finish(proc)
        refused = self.invoke("release", "samsung")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("ada", refused.stderr)
        self.assertNotIn("Traceback", refused.stderr)
        self.assertTrue(pid_alive(pid))
        self.assertIn("held by ada", self.who("samsung"))
        forced = self.invoke_ok("release", "samsung", "--force")
        self.assertIn("ada", forced.stdout)
        self.assertIn("was held by ada", forced.stdout)
        self.wait_for(lambda: (not pid_alive(pid)) or "")
        self.assertIn("samsung: free", self.who("samsung"))

    def test_who_spots_a_holder_whose_session_is_gone(self):
        proc = subprocess.Popen(
            [sys.executable, str(SCRIPT), "take", "samsung", "--who", "ada", "--minutes", "2"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env(),
            start_new_session=True,
        )
        self.procs.append(proc)
        pid = self.finish(proc)
        status = self.who("samsung")
        self.assertIn("owning session is gone", status)
        self.assertIn(f"held by ada pid {pid} (alive)", status)
        self.assertNotIn("stale", status)
        self.assertTrue(pid_alive(pid))

    def test_who_lists_both_devices(self):
        self.taken("crosstune-phone", "ada")
        status = self.who()
        self.assertIn("samsung: free", status)
        self.assertIn("crosstune-phone: held by ada", status)

    def test_the_two_devices_do_not_share_a_lock(self):
        self.taken("samsung", "ada")
        self.taken("crosstune-phone", "bea", timeout=5)
        self.assertTrue((self.cache / "samsung.lock").is_file())
        self.assertTrue((self.cache / "crosstune-phone.lock").is_file())
        self.assertEqual(self.note("samsung")["who"], "ada")
        self.assertEqual(self.note("crosstune-phone")["who"], "bea")

    def test_an_unknown_device_names_the_real_ones(self):
        result = self.invoke("take", "tablet", "--who", "ada", "--minutes", "1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Traceback", result.stderr)
        for device in DEVICES:
            self.assertIn(device, result.stderr)

    def test_a_missing_cache_says_where_it_should_be(self):
        missing = Path(self.tmp.name) / "no-cache"
        result = self.invoke("who", env=self.env(CROSSTUNE_AGENT_CACHE=str(missing)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(missing), result.stderr)
        self.assertIn("/mnt/data/agent-cache", result.stderr)
        self.assertIn(".cache/agent-cache", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_elsewhere_the_cache_is_under_home(self):
        home = Path(self.tmp.name) / "home"
        home.mkdir()
        env = os.environ.copy()
        env.pop("CROSSTUNE_AGENT_CACHE", None)
        env["HOME"] = str(home)
        env["CROSSTUNE_LOCK_HOSTNAME"] = "laptop"
        env["PYTHONUNBUFFERED"] = "1"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "who"],
            capture_output=True, text=True, env=env, timeout=15,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(home / ".cache" / "agent-cache"), result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_zero_minutes_and_a_blank_name_are_refused(self):
        refused = self.invoke("take", "samsung", "--who", "ada", "--minutes", "0")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("greater than 0", refused.stderr)
        self.assertNotIn("Traceback", refused.stderr)
        blank = self.invoke("take", "samsung", "--who", "  ", "--minutes", "1")
        self.assertNotEqual(blank.returncode, 0)
        self.assertIn("--who", blank.stderr)
        self.assertFalse((self.cache / "samsung.lock").exists())

    def test_release_of_a_free_device_says_so(self):
        result = self.invoke_ok("release", "crosstune-phone")
        self.assertIn("free", result.stdout)

    def spawn_session(self, *args):
        """A take from its own session, as another helper would call it."""
        proc = subprocess.Popen(
            [sys.executable, str(SCRIPT), *args],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env(),
            start_new_session=True,
        )
        self.procs.append(proc)
        return proc

    def test_an_earlier_waiter_is_served_before_a_later_one(self):
        # The Deezer helper queued first. The holder's own later take must not be
        # the one flock wakes, and it must not keep the device while others wait.
        self.taken("crosstune-phone", "chord")
        early = self.spawn_session("take", "crosstune-phone", "--who", "deezer", "--minutes", "2")
        self.wait_for(lambda: "waiting: deezer" in self.who("crosstune-phone"))
        later = self.spawn_session("take", "crosstune-phone", "--who", "chord", "--minutes", "2")
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and later.poll() is None and "waiting: chord" not in self.who("crosstune-phone"):
            time.sleep(0.05)
        if later.poll() is not None:
            out, err = later.communicate()
            self.fail(f"the holder's later take got the device while deezer was already waiting\n{out}\n{err}")
        status = self.who("crosstune-phone")
        self.assertLess(status.index("waiting: deezer"), status.index("waiting: chord"), status)
        self.invoke_ok("release", "crosstune-phone")
        early_pid = self.finish(early, timeout=10)
        self.assertIsNone(later.poll(), "the later waiter got the device before the earlier one")
        status = self.who("crosstune-phone")
        self.assertIn(f"held by deezer pid {early_pid}", status)
        self.assertIn("waiting: chord", status)
        self.assertLess(status.index("held by deezer"), status.index("waiting: chord"), status)

    def test_who_reads_the_holder_from_the_lock_not_an_overwritten_note(self):
        pid = self.taken("samsung", "ada")
        self.assertNotIn("ada", (self.cache / "samsung.lock").read_text())
        (self.cache / "samsung.lock").write_text('{"who": "Free", "pid": 1, "start": "x", "until": "y"}\n')
        for path in (self.cache / "holders").glob("*.json"):
            path.write_text(
                '{"device": "samsung", "pid": 1, "session": 1, "start": "x", "until": "y", "who": "Free"}\n'
            )
        status = self.who("samsung")
        self.assertIn(f"held by ada pid {pid} (alive)", status)
        self.assertNotIn("Free", status)
        self.assertNotIn("samsung: free", status)
        released = self.invoke_ok("release", "samsung")
        self.assertIn("released samsung", released.stdout)
        self.wait_for(lambda: (not pid_alive(pid)) or "")
        self.assertTrue(pid_alive(1))
        self.assertIn("samsung: free", self.who("samsung"))

    def test_a_plain_flock_cannot_take_the_device_while_devicelock_holds_it(self):
        self.taken("samsung", "ada")
        fd = os.open(self.cache / "samsung.lock", os.O_RDWR)
        try:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            os.close(fd)
        self.invoke_ok("release", "samsung")
        fd = os.open(self.cache / "samsung.lock", os.O_RDWR)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)
        self.assertIn("samsung: free", self.who("samsung"))

    def test_who_reports_a_plain_flock_and_release_leaves_it(self):
        path = self.cache / "samsung.lock"
        fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            status = self.who("samsung")
            self.assertIn(f"held by pid {os.getpid()} (alive)", status)
            self.assertNotIn("samsung: free", status)
            refused = self.invoke("release", "samsung", "--force")
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn(str(os.getpid()), refused.stderr)
            self.assertNotIn("Traceback", refused.stderr)
            probe = os.open(path, os.O_RDWR)
            try:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(probe)
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)
        self.assertIn("samsung: free", self.who("samsung"))


if __name__ == "__main__":
    unittest.main()
