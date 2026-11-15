#!/usr/bin/env python3
"""Measure final timing alignment on recorded replay inputs in the bounded Kotlin container.

First run syncreplay.py untimed for the same IDs to record its TSV inputs.
The benchmark reads those files without changing any replay request or result.
"""
import argparse
import subprocess

import syncreplay as replay


def run(ids, timing=None):
    paths = [replay.own() / 'untimed' / f'{song}.tsv' for song in ids]
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise SystemExit('Run syncreplay.py untimed first. Missing: ' + ', '.join(missing))
    sources = [replay.ROOT / name for name in replay.UNTIMED_TIMING] + [replay.ROOT / 'scripts/syncreplay/TimingBenchmark.kt']
    classes = replay.untimed_classes(replay.timing_copied(timing, sources) if timing else sources)
    library = next(str(jar) for jar in replay.kotlin_jars() if jar.name.startswith('kotlin-stdlib-'))
    subprocess.run(replay.replay_container((replay.OUT, replay.OUT, 'ro'), (replay.own(), replay.own(), 'ro')) +
                   ['java', '-cp', f'{classes}:{library}', 'com.astrovm.crosstune.TimingBenchmarkKt', *map(str, paths)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ids', nargs='+', help='recordings whose TSV inputs already exist')
    parser.add_argument('--timing', help='another SungTiming.kt to measure on the same inputs')
    args = parser.parse_args()
    run(args.ids, args.timing)
