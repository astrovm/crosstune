#!/usr/bin/env python3
"""Trim the oldest idle cache parts while respecting gradle-box slot locks."""
import fcntl
import os
from pathlib import Path
import shutil
import sys

state = Path(sys.argv[1])
own_slot = int(sys.argv[2])
limit = int(os.environ.get("GRADLE_BOX_CACHE_BYTES", 40 * 1024**3))


def size(path):
    total = 0
    for root, dirs, files in os.walk(path):
        for name in dirs + files:
            try:
                total += os.lstat(Path(root) / name).st_blocks * 512
            except FileNotFoundError:
                pass
    return total


def acquire(number):
    lock = (state / f"slot-{number}.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        return None
    return lock


with (state / "cleanup.lock").open("a") as cleanup_lock:
    fcntl.flock(cleanup_lock, fcntl.LOCK_EX)
    homes = [p for p in state.glob("gradle-*") if p.is_dir() and p.name[7:].isdigit()]
    seed = state / "seed"
    total = size(state)
    removed = []
    for part in sorted(homes, key=lambda p: (p.stat().st_mtime_ns, p.name)):
        if total <= limit:
            break
        number = int(part.name[7:])
        # Keep the last source of downloads when there is no seed.
        if not seed.exists() and not any(
            (h / "caches/modules-2").is_dir() for h in homes if h != part and h.exists()
        ) and (part / "caches/modules-2").is_dir():
            continue
        locks = []
        numbers = {number} - {own_slot}
        for number in sorted(numbers):
            lock = acquire(number)
            if lock is None:
                break
            locks.append(lock)
        else:
            before = size(part)
            shutil.rmtree(part)
            total -= before
            removed.append(f"{part.name} ({before} bytes)")
        for lock in locks:
            lock.close()
    if removed:
        print("gradle-box: deleted " + ", ".join(removed), flush=True)
    if total > limit:
        print(f"gradle-box: cache is {total} bytes, above {limit}; in-use or last warm cache retained", file=sys.stderr)
