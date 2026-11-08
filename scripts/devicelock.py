#!/usr/bin/env python3
"""Hold a shared test device so agents take turns.

  python3 scripts/devicelock.py take DEVICE --who TEXT --minutes N
  python3 scripts/devicelock.py release DEVICE [--force]
  python3 scripts/devicelock.py who [DEVICE]

DEVICE is samsung or crosstune-phone. The locks are samsung.lock and
crosstune-phone.lock in the agent cache: /mnt/data/agent-cache on mr, and
~/.cache/agent-cache elsewhere. take waits until it holds the lock, writes who
has it, the holder pid, the start and the until time, and leaves that hold to a
detached process that lets go after N minutes (a fraction is fine). Taking a
lock this --who already holds returns at once and prints that holder pid.
release ends the holder this session took. --force ends someone else's and
prints who that was.
"""
import argparse
import fcntl
import json
import math
import os
import platform
import signal
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

DEVICES = ("samsung", "crosstune-phone")


def die(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def agent_cache():
    """The shared cache. CROSSTUNE_AGENT_CACHE points tests at a temp directory."""
    override = os.environ.get("CROSSTUNE_AGENT_CACHE")
    if override:
        path = Path(override).expanduser()
    else:
        # CROSSTUNE_LOCK_HOSTNAME lets a test check the non-mr path without touching mr's cache.
        host = os.environ.get("CROSSTUNE_LOCK_HOSTNAME", platform.node().split(".")[0])
        path = Path("/mnt/data/agent-cache") if host == "mr" else Path.home() / ".cache" / "agent-cache"
    if not path.is_dir():
        die(
            f"The agent cache is missing: {path}\n"
            "On mr it is /mnt/data/agent-cache. Elsewhere it is ~/.cache/agent-cache."
        )
    return path


def require_device(device):
    if device not in DEVICES:
        die(f"No device called {device}. There are: {', '.join(DEVICES)}.")
    return device


def lock_path(device):
    return agent_cache() / f"{device}.lock"


def pid_alive(pid):
    """True while pid is running. A zombie no longer holds its file locks."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
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
    except OSError:
        return False
    return True


def process_command(pid):
    path = Path(f"/proc/{pid}/cmdline")
    if path.exists():
        try:
            return path.read_bytes().replace(b"\x00", b" ").decode(errors="replace")
        except OSError:
            return ""
    # macOS has no /proc. args= is accepted by both macOS and Linux ps.
    listed = subprocess.run(["ps", "-p", str(pid), "-o", "args="], capture_output=True, text=True)
    return listed.stdout


def holder_alive(pid):
    if not pid_alive(pid):
        return False
    command = process_command(pid)
    return "devicelock.py" in command and "_hold" in command


def read_note(path):
    try:
        raw = Path(path).read_text()
    except FileNotFoundError:
        return None
    except OSError:
        return None
    raw = raw.strip()
    if not raw:
        return None
    try:
        note = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(note, dict) or "who" not in note or "pid" not in note:
        return None
    return note


def write_note(fd, note):
    # The note lives in the lock file itself. Replacing that file would be a new
    # inode, and waiters blocked on the old one would never see the release.
    data = (json.dumps(note, sort_keys=True) + "\n").encode()
    os.lseek(fd, 0, os.SEEK_SET)
    os.ftruncate(fd, 0)
    os.write(fd, data)
    os.fsync(fd)


def clear_fd(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    os.ftruncate(fd, 0)
    os.fsync(fd)


def waiter_dir(device):
    return agent_cache() / "waiters" / device


def write_waiter(device, who):
    directory = waiter_dir(device)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{os.getpid()}.json"
    record = {
        "who": who,
        "pid": os.getpid(),
        "since": datetime.now().astimezone().replace(microsecond=0).isoformat(timespec="seconds"),
    }
    path.write_text(json.dumps(record, sort_keys=True) + "\n")
    return path


def waiters_of(device, holder_pid):
    directory = agent_cache() / "waiters" / device
    if not directory.is_dir():
        return []
    found = []
    for path in sorted(directory.glob("*.json")):
        try:
            record = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        pid = record.get("pid")
        if pid == holder_pid or not pid_alive(pid):
            path.unlink(missing_ok=True)
            continue
        found.append(record)
    return found


def session_of(note):
    try:
        return int(note.get("session"))
    except (TypeError, ValueError):
        return -1


def ours(note):
    return session_of(note) == os.getsid(0)


def describe(device):
    note = read_note(lock_path(device))
    holder_pid = note.get("pid") if note else None
    if not note or not note.get("start"):
        lines = [f"{device}: free"]
    else:
        alive = holder_alive(holder_pid)
        state = "alive" if alive else "dead"
        when = f"pid {holder_pid} ({state}) since {note['start']} until {note['until']}"
        if alive:
            gone = "" if pid_alive(session_of(note)) else "; owning session is gone"
            lines = [f"{device}: held by {note['who']} {when}{gone}"]
        else:
            lines = [f"{device}: stale, last held by {note['who']} {when}"]
    for record in waiters_of(device, holder_pid):
        lines.append(f"  waiting: {record['who']} pid {record['pid']} since {record['since']}")
    return "\n".join(lines)


def check_minutes(minutes):
    if isinstance(minutes, bool) or not isinstance(minutes, (int, float)) or not math.isfinite(minutes) or minutes <= 0:
        die("Pass --minutes as a number of minutes greater than 0.")
    return float(minutes)


def check_who(who):
    who = (who or "").strip()
    if not who:
        die("Pass --who with the name of whoever is taking the lock.")
    return who


def take(device, who, minutes):
    device = require_device(device)
    who = check_who(who)
    minutes = check_minutes(minutes)
    path = lock_path(device)
    note = read_note(path)
    if note and note.get("who") == who and holder_alive(note.get("pid")):
        # Same helper, still holding. Queueing here is the self-wait that stuck a
        # holder behind its own orphaned sleep.
        print(f"{device} is already held by {who}", file=sys.stderr)
        print(note["pid"])
        return

    read_fd, write_fd = os.pipe()
    os.set_inheritable(write_fd, True)
    env = os.environ.copy()
    env["CROSSTUNE_LOCK_SESSION"] = str(os.getsid(0))
    script = str(Path(__file__).resolve())
    proc = subprocess.Popen(
        [sys.executable, script, "_hold", device, "--who", who, "--minutes", str(minutes), "--ready-fd", str(write_fd)],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True, close_fds=True, pass_fds=(write_fd,), env=env,
    )
    os.close(write_fd)
    ready = False

    def cancel(signum, _frame):
        # The holder is in its own session, so this process exiting would leave it
        # waiting, and it would take the lock with nobody left to release it.
        if not ready:
            try:
                os.kill(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGINT, cancel)
    signal.signal(signal.SIGTERM, cancel)
    with os.fdopen(read_fd) as pipe:
        line = pipe.readline().strip()
    ready = True
    if line == "ok":
        # The holder has to outlive this command. Marking it finished keeps Python
        # from warning, and from waiting, when this process exits.
        print(proc.pid)
        proc.returncode = 0
        return
    proc.wait(timeout=5)
    if line.startswith("error:"):
        die(line[6:].strip() or "The holder stopped before it got the lock.")
    die("The holder stopped before it got the lock.")


def hold(device, who, minutes, ready_fd):
    """Keep the flock until the minutes run out or release signals this process."""
    device = require_device(device)
    who = check_who(who)
    minutes = check_minutes(minutes)
    try:
        session = int(os.environ["CROSSTUNE_LOCK_SESSION"])
    except (KeyError, ValueError):
        die("The holder needs the caller's session.")
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGPIPE, signal.SIG_IGN)

    def stop(_signum, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    path = lock_path(device)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o644)
    waiter = write_waiter(device, who)
    held = False
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        held = True
        waiter.unlink(missing_ok=True)
        start = datetime.now().astimezone().replace(microsecond=0)
        until = start + timedelta(minutes=minutes)
        write_note(fd, {
            "pid": os.getpid(),
            "session": session,
            "start": start.isoformat(timespec="seconds"),
            "until": until.isoformat(timespec="seconds"),
            "who": who,
        })
        try:
            os.write(ready_fd, b"ok\n")
        except OSError:
            pass
        os.close(ready_fd)
        ready_fd = -1
        deadline = time.monotonic() + minutes * 60
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.05, remaining))
    except SystemExit:
        raise
    except Exception as error:
        try:
            os.write(ready_fd, f"error: {error}\n".encode())
        except OSError:
            pass
        raise SystemExit(1) from None
    finally:
        waiter.unlink(missing_ok=True)
        if held:
            try:
                clear_fd(fd)
                fcntl.flock(fd, fcntl.LOCK_UN)
            except OSError:
                pass
        os.close(fd)
        if ready_fd >= 0:
            os.close(ready_fd)


def clear_if_still(path, pid):
    """Drop a note whose holder is already gone, and leave a newer hold alone."""
    if not path.exists():
        return
    fd = os.open(path, os.O_RDWR | os.O_CLOEXEC)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        current = read_note_fd(fd)
        if current and int(current.get("pid", -1)) == pid:
            clear_fd(fd)
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def read_note_fd(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    raw = os.read(fd, 65536).decode(errors="replace").strip()
    if not raw:
        return None
    try:
        note = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return note if isinstance(note, dict) else None


def release(device, force):
    device = require_device(device)
    path = lock_path(device)
    note = read_note(path)
    if note is None:
        print(f"{device} is free")
        return
    owner = note.get("who", "unknown")
    if not ours(note) and not force:
        die(f"{device} is held by {owner} (pid {note.get('pid')}), not this session. Pass --force to end it.")
    if force and not ours(note):
        print(f"{device} was held by {owner}")
    try:
        pid = int(note.get("pid") or 0)
    except (TypeError, ValueError):
        pid = 0
    if holder_alive(pid):
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 5
        while holder_alive(pid) and time.monotonic() < deadline:
            time.sleep(0.05)
        if holder_alive(pid):
            os.kill(pid, signal.SIGKILL)
            deadline = time.monotonic() + 2
            while pid_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.05)
    clear_if_still(path, pid)
    print(f"released {device}")


def who(device):
    if device is None:
        print("\n".join(describe(name) for name in DEVICES))
        return
    print(describe(require_device(device)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    take_cmd = commands.add_parser("take", help="wait for the lock, then hold it in the background")
    take_cmd.add_argument("device")
    take_cmd.add_argument("--who", required=True)
    take_cmd.add_argument("--minutes", required=True, type=float)
    release_cmd = commands.add_parser("release", help="end this session's hold")
    release_cmd.add_argument("device")
    release_cmd.add_argument("--force", action="store_true", help="end someone else's hold and print who it was")
    who_cmd = commands.add_parser("who", help="show who holds the device and who is waiting")
    who_cmd.add_argument("device", nargs="?")
    hold_cmd = commands.add_parser("_hold", help=argparse.SUPPRESS)
    hold_cmd.add_argument("device")
    hold_cmd.add_argument("--who", required=True)
    hold_cmd.add_argument("--minutes", required=True, type=float)
    hold_cmd.add_argument("--ready-fd", required=True, type=int)
    args = parser.parse_args()
    if args.command == "take":
        take(args.device, args.who, args.minutes)
    elif args.command == "release":
        release(args.device, args.force)
    elif args.command == "who":
        who(args.device)
    else:
        hold(args.device, args.who, args.minutes, args.ready_fd)


if __name__ == "__main__":
    main()
