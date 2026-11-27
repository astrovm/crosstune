#!/usr/bin/env python3
"""Prepare found songs in separate processes, with the shared replay resource limits.

Run syncreplay.py set --find-only first. The priority IDs go first; the remaining
songs follow. Run set --cached afterwards to refresh the sheets for the recordings.
The replay cache and Python environment are selected by SYNCREPLAY_DIR and the
Python used to run this script, as for syncreplay.py itself.
"""

import argparse
import concurrent.futures
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def order_songs(songs, first, only_first=False):
    eligible = {song['id']: song for song in songs if song.get('id') and song.get('lines')}
    missing = [song for song in first if song not in eligible]
    if missing:
        raise ValueError(f"No found lyrics for: {', '.join(missing)}")
    return list(dict.fromkeys(first if only_first else first + list(eligible)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--first', nargs='*', default=[])
    parser.add_argument('--only-first', action='store_true', help='prepare only the IDs passed to --first')
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    cache = Path(os.environ['SYNCREPLAY_DIR'])
    try:
        songs = order_songs(json.loads((cache / 'set.json').read_text()), args.first, args.only_first)
    except ValueError as error:
        parser.error(str(error))
    logs = cache / 'prep-logs'
    logs.mkdir(parents=True, exist_ok=True)
    threads = int(os.environ.get('SYNCREPLAY_HEARD_THREADS', '1'))
    if threads < 1:
        parser.error('SYNCREPLAY_HEARD_THREADS must be positive')
    slots = os.environ.get('SYNCREPLAY_HEARING_SLOTS', str(max(1, 9 // threads)))
    if not slots.isdigit() or int(slots) < 1:
        parser.error('SYNCREPLAY_HEARING_SLOTS must be positive')
    environment = os.environ | {'SYNCREPLAY_TRUTH_THREADS': '2', 'SYNCREPLAY_HEARING_SLOTS': slots, 'OMP_NUM_THREADS': '1'}
    replay = Path(__file__).with_name('syncreplay.py')
    started = time.monotonic()

    def prepare(song):
        begin = time.monotonic()
        print(f'{song}: starting', flush=True)
        with (logs / f'{song}.log').open('w') as output:
            result = subprocess.run([sys.executable, str(replay), 'prep', song, str(cache / f'{song}.lyrics.json')],
                                    stdout=output, stderr=subprocess.STDOUT, env=environment, check=False)
        elapsed = time.monotonic() - begin
        print(f'{song}: {elapsed:.1f} s, exit {result.returncode}', flush=True)
        return song, result.returncode

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(prepare, songs))
    failed = [song for song, code in results if code]
    print(f'Total: {time.monotonic() - started:.1f} s; failed: {", ".join(failed) or "none"}', flush=True)
    return bool(failed)


if __name__ == '__main__':
    sys.exit(main())
