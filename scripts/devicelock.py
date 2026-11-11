#!/usr/bin/env python3
"""Hold a shared test device so agents take turns.

  python3 scripts/devicelock.py take DEVICE --who TEXT --minutes N
  python3 scripts/devicelock.py release DEVICE [--who TEXT] [--force]
  python3 scripts/devicelock.py who [DEVICE]

DEVICE is samsung, or a test emulator in scripts/emulator/emulators.json:
crosstune-phone or crosstune-tv. The lock is DEVICE.lock in the agent cache:
/mnt/data/agent-cache on mr, and ~/.cache/agent-cache elsewhere. take waits in line. An earlier waiter gets
the device before a later one, including when the holder takes it again:
that new take goes to the back. flock(2) stays held for the whole turn, so
a plain flock on the same file waits too. who reads the holder from that
lock. The name and the times live beside it, and writing over the lock file
does not change who holds it. Taking a lock this --who already holds, while
nobody is waiting, returns at once and prints that holder pid. release ends
the holder this session took, or, with --who, the one taken with that name:
an agent's shell calls each run in their own session. --force ends someone
else's and prints who that was. The holder lets go after N minutes (a fraction is fine).
"""
import argparse
import fcntl
import json
import math
import os
import platform
import signal
import struct
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

# The Samsung, and each test emulator emulator.py makes, which agents share the same way.
DEVICES = ("samsung", *json.loads((Path(__file__).resolve().parent / "emulator" / "emulators.json").read_text()))
# Long enough for a waiter already blocked in flock(2) to be woken before we
# join the kernel queue again. An immediate relock jumps that waiter.
YIELD_SECONDS = 0.05


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


def argv_of(pid):
    path = Path(f"/proc/{pid}/cmdline")
    if path.exists():
        try:
            raw = path.read_bytes()
        except OSError:
            raw = b""
        if raw:
            return [part.decode(errors="replace") for part in raw.split(b"\x00") if part]
    # macOS has no /proc. args= is accepted by both macOS and Linux ps.
    listed = subprocess.run(["ps", "-p", str(pid), "-o", "args="], capture_output=True, text=True)
    return listed.stdout.split()


def flag(argv, name):
    try:
        index = argv.index(name)
    except ValueError:
        return None
    if index + 1 >= len(argv):
        return None
    return argv[index + 1]


def is_our_holder(argv, device=None):
    if not argv or "_hold" not in argv:
        return False
    if not any(part.endswith("devicelock.py") for part in argv):
        return False
    index = argv.index("_hold")
    held = argv[index + 1] if index + 1 < len(argv) else None
    if device is not None and held != device:
        return False
    return True


def holder_alive(pid, device=None):
    if not pid_alive(pid):
        return False
    return is_our_holder(argv_of(pid), device)


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


def holder_path(pid):
    return agent_cache() / "holders" / f"{int(pid)}.json"


def write_holder_note(note):
    """The lock file stays empty. Its bytes are not the holder, and anyone can write them."""
    path = holder_path(note["pid"])
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.parent / f".{note['pid']}.{os.getpid()}.tmp"
    temporary.write_text(json.dumps(note, sort_keys=True) + "\n")
    os.replace(temporary, path)


def checked_note(pid, who, device):
    note = read_note(holder_path(pid))
    if not note:
        return None
    if note.get("pid") != pid or note.get("who") != who or note.get("device") != device:
        return None
    if not note.get("start") or not note.get("until"):
        return None
    return note


def sweep_holder_notes():
    directory = agent_cache() / "holders"
    if not directory.is_dir():
        return
    for path in directory.glob("*.json"):
        try:
            pid = int(path.stem)
        except ValueError:
            continue
        if not pid_alive(pid):
            path.unlink(missing_ok=True)


def granted_flock_pid(line, major, minor, ino):
    """One granted FLOCK WRITE line from /proc/locks, or None.

    A blocked waiter is a different shape (`ID: -> FLOCK ...`) and is not the holder.
    """
    parts = line.split()
    if len(parts) < 6 or parts[1] != "FLOCK" or parts[3] != "WRITE":
        return None
    try:
        pid = int(parts[4])
        dev_major, dev_minor, dev_ino = parts[5].split(":")
        if int(dev_major, 16) != major or int(dev_minor, 16) != minor or int(dev_ino) != ino:
            return None
    except (ValueError, IndexError):
        return None
    return pid


def flock_pid_proc(st):
    try:
        text = Path("/proc/locks").read_text()
    except OSError:
        return None
    major = os.major(st.st_dev)
    minor = os.minor(st.st_dev)
    found = []
    for line in text.splitlines():
        pid = granted_flock_pid(line, major, minor, st.st_ino)
        if pid is not None:
            found.append(pid)
    for pid in found:
        if holder_alive(pid):
            return pid
    return found[0] if found else None


def flock_pid_fcntl(path):
    """macOS has no /proc/locks. The POSIX write lock taken beside flock names the holder."""
    if sys.platform != "darwin":
        return None
    fd = os.open(path, os.O_RDWR | os.O_CLOEXEC)
    try:
        query = struct.pack("qqihh", 0, 0, 0, fcntl.F_WRLCK, os.SEEK_SET)
        try:
            result = fcntl.fcntl(fd, fcntl.F_GETLK, query)
        except OSError:
            return None
        _start, _length, pid, kind, _whence = struct.unpack("qqihh", result)
        if kind == fcntl.F_UNLCK or pid <= 0:
            return None
        return pid
    finally:
        os.close(fd)


def kernel_holder(device):
    """Pid that holds the device lock, from the kernel. The lock file's bytes are ignored."""
    path = lock_path(device)
    try:
        st = path.stat()
    except OSError:
        return None
    if Path("/proc/locks").exists():
        # On Linux, F_GETLK does not see flock(2). An empty /proc/locks means free.
        return flock_pid_proc(st)
    return flock_pid_fcntl(path)


def set_posix_write(fd):
    if sys.platform != "darwin":
        return
    data = struct.pack("qqihh", 0, 0, 0, fcntl.F_WRLCK, os.SEEK_SET)
    fcntl.fcntl(fd, fcntl.F_SETLK, data)


def clear_posix(fd):
    if sys.platform != "darwin":
        return
    data = struct.pack("qqihh", 0, 0, 0, fcntl.F_UNLCK, os.SEEK_SET)
    try:
        fcntl.fcntl(fd, fcntl.F_SETLK, data)
    except OSError:
        pass


def waiter_dir(device):
    return agent_cache() / "waiters" / device


def stamp():
    return datetime.now().astimezone().replace(microsecond=0).isoformat(timespec="seconds")


def enqueue(device, who):
    """Next ticket, and the waiter file, published before anyone can take a later one."""
    path = agent_cache() / f"{device}.queue"
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        os.lseek(fd, 0, os.SEEK_SET)
        raw = os.read(fd, 128).decode().strip()
        try:
            current = int(raw)
        except ValueError:
            current = 0
        ticket = current + 1
        os.lseek(fd, 0, os.SEEK_SET)
        os.ftruncate(fd, 0)
        os.write(fd, f"{ticket}\n".encode())
        os.fsync(fd)
        directory = waiter_dir(device)
        directory.mkdir(parents=True, exist_ok=True)
        record = {"pid": os.getpid(), "since": stamp(), "ticket": ticket, "who": who}
        waiter = directory / f"{ticket:020d}.json"
        temporary = directory / f".{ticket:020d}.{os.getpid()}.tmp"
        temporary.write_text(json.dumps(record, sort_keys=True) + "\n")
        os.replace(temporary, waiter)
        return ticket, waiter
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def live_waiters(device):
    directory = waiter_dir(device)
    if not directory.is_dir():
        return []
    found = []
    for path in directory.glob("*.json"):
        try:
            record = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(record, dict):
            continue
        pid = record.get("pid")
        ticket = record.get("ticket")
        if isinstance(ticket, bool) or not isinstance(ticket, int):
            if not pid_alive(pid):
                path.unlink(missing_ok=True)
            continue
        if not pid_alive(pid):
            path.unlink(missing_ok=True)
            continue
        found.append(record)
    found.sort(key=lambda record: record["ticket"])
    return found


def is_head(device, ticket):
    tickets = [record["ticket"] for record in live_waiters(device)]
    if ticket in tickets:
        return tickets[0] == ticket
    return not tickets


def session_of_holder(pid):
    # The holder carries its session in its own arguments: reading another process's environment would pull in
    # whatever secrets it was started with.
    text = flag(argv_of(pid), "--session")
    try:
        return int(text)
    except (TypeError, ValueError):
        return -1


def ours(pid):
    return session_of_holder(pid) == os.getsid(0)


def waiter_name(record):
    pid = record.get("pid")
    argv = argv_of(pid)
    if is_our_holder(argv):
        return flag(argv, "--who") or record.get("who") or "unknown"
    return record.get("who") or "unknown"


def describe(device):
    sweep_holder_notes()
    pid = kernel_holder(device)
    if pid is not None and not pid_alive(pid):
        pid = None
    if pid is None:
        lines = [f"{device}: free"]
    else:
        argv = argv_of(pid)
        if is_our_holder(argv, device):
            who = flag(argv, "--who") or "unknown"
            note = checked_note(pid, who, device)
            if note:
                when = f"pid {pid} (alive) since {note['start']} until {note['until']}"
            else:
                when = f"pid {pid} (alive)"
            session = session_of_holder(pid)
            gone = "; owning session is gone" if session > 0 and not pid_alive(session) else ""
            lines = [f"{device}: held by {who} {when}{gone}"]
        else:
            lines = [f"{device}: held by pid {pid} (alive)"]
    for record in live_waiters(device):
        if record.get("pid") == pid:
            continue
        lines.append(
            f"  waiting: {waiter_name(record)} pid {record.get('pid')} since {record.get('since')}"
        )
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


def same_hold(device, who):
    """The pid to print when this who already holds the device and nobody is waiting."""
    pid = kernel_holder(device)
    if not pid or not holder_alive(pid, device):
        return None
    if flag(argv_of(pid), "--who") != who:
        return None
    for record in live_waiters(device):
        if record.get("pid") != pid:
            return None
    return pid


def take(device, who, minutes):
    device = require_device(device)
    who = check_who(who)
    minutes = check_minutes(minutes)
    current = same_hold(device, who)
    if current is not None:
        # Nobody is waiting, so this is the same hold. Queueing here is the
        # self-wait that stuck a holder behind its own orphaned sleep.
        print(f"{device} is already held by {who}", file=sys.stderr)
        print(current)
        return

    read_fd, write_fd = os.pipe()
    os.set_inheritable(write_fd, True)
    script = str(Path(__file__).resolve())
    proc = subprocess.Popen(
        [
            sys.executable, script, "_hold", device,
            "--who", who,
            "--minutes", str(minutes),
            "--session", str(os.getsid(0)),
            "--ready-fd", str(write_fd),
        ],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True, close_fds=True, pass_fds=(write_fd,),
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


def hold(device, who, minutes, session, ready_fd):
    """Keep the flock until the minutes run out or release signals this process."""
    device = require_device(device)
    who = check_who(who)
    minutes = check_minutes(minutes)
    if session <= 0:
        die("The holder needs the caller's session.")
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGPIPE, signal.SIG_IGN)

    def stop(_signum, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # Replacing this file would be a new inode, and waiters blocked on the old one would never wake.
    path = lock_path(device)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o644)
    waiter = None
    locked = False
    kept = False
    try:
        ticket, waiter = enqueue(device, who)
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
            except InterruptedError:
                continue
            locked = True
            if is_head(device, ticket):
                kept = True
                break
            fcntl.flock(fd, fcntl.LOCK_UN)
            locked = False
            time.sleep(YIELD_SECONDS)
        waiter.unlink(missing_ok=True)
        start = datetime.now().astimezone().replace(microsecond=0)
        until = start + timedelta(minutes=minutes)
        write_holder_note({
            "device": device,
            "pid": os.getpid(),
            "session": session,
            "start": start.isoformat(timespec="seconds"),
            "ticket": ticket,
            "until": until.isoformat(timespec="seconds"),
            "who": who,
        })
        set_posix_write(fd)
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
        if waiter is not None:
            waiter.unlink(missing_ok=True)
        if kept:
            holder_path(os.getpid()).unlink(missing_ok=True)
        if locked:
            try:
                clear_posix(fd)
                fcntl.flock(fd, fcntl.LOCK_UN)
            except OSError:
                pass
        os.close(fd)
        if ready_fd >= 0:
            os.close(ready_fd)


def release(device, force, name=None):
    device = require_device(device)
    pid = kernel_holder(device)
    if pid is None or not pid_alive(pid):
        print(f"{device} is free")
        return
    argv = argv_of(pid)
    if not is_our_holder(argv, device):
        die(f"{device} is held by pid {pid}, which is not a devicelock holder. Not ending it.")
    who = flag(argv, "--who") or "unknown"
    mine = ours(pid) or (name is not None and name.strip() == who)
    if not mine and not force:
        die(f"{device} is held by {who} (pid {pid}), not this session. Pass --who with its name, or --force to end it.")
    if force and not mine:
        print(f"{device} was held by {who}")
    if holder_alive(pid, device):
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 5
        while holder_alive(pid, device) and time.monotonic() < deadline:
            time.sleep(0.05)
        if holder_alive(pid, device):
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            deadline = time.monotonic() + 2
            while pid_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.05)
    if not pid_alive(pid):
        holder_path(pid).unlink(missing_ok=True)
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
    release_cmd.add_argument("--who", help="the name it was taken with, to release it from another shell session")
    release_cmd.add_argument("--force", action="store_true", help="end someone else's hold and print who it was")
    who_cmd = commands.add_parser("who", help="show who holds the device and who is waiting")
    who_cmd.add_argument("device", nargs="?")
    hold_cmd = commands.add_parser("_hold", help=argparse.SUPPRESS)
    hold_cmd.add_argument("device")
    hold_cmd.add_argument("--who", required=True)
    hold_cmd.add_argument("--minutes", required=True, type=float)
    hold_cmd.add_argument("--session", required=True, type=int)
    hold_cmd.add_argument("--ready-fd", required=True, type=int)
    args = parser.parse_args()
    if args.command == "take":
        take(args.device, args.who, args.minutes)
    elif args.command == "release":
        release(args.device, args.force, args.who)
    elif args.command == "who":
        who(args.device)
    else:
        hold(args.device, args.who, args.minutes, args.session, args.ready_fd)


if __name__ == "__main__":
    main()
