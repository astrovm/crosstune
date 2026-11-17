#!/usr/bin/env python3
"""Benchmark the real timing code on recorded or fake songs, in the bounded replay container.

The --fake 34- and 60-line songs repeat their chorus and their hearing. They expose the
many partial matches that made pairwise chain searches slow. One call warms the
JVM before three measured calls. --timing can measure an older SungTiming.kt with
the same inputs.
"""
import argparse
import random
import subprocess
from pathlib import Path

import syncreplay as replay


def inputs():
    """The same two fake songs each run. No recording or network is needed."""
    randomizer = random.Random(11)
    vocabulary = ''.join(chr(0x4E00 + 37 * k) for k in range(200))
    folder = replay.own() / 'timing-bench'
    folder.mkdir(exist_ok=True)
    paths = []
    for count, width in ((34, 12), (60, 20)):
        verses = [''.join(randomizer.choice(vocabulary) for _ in range(width)) for _ in range(max(count * 2 // 3, count - 18))]
        chorus = [''.join(randomizer.choice(vocabulary) for _ in range(width)) for _ in range(6)]
        lines = (verses[:len(verses) // 2] + chorus + verses[len(verses) // 2:] + chorus + chorus)[:count]
        words, start = [], 5_000
        for line in lines:
            at = 0
            while at < len(line):
                size = randomizer.randint(3, 8)
                words.append((line[at:at + size], start + 550 + at * 4 * 70))
                at += size
            start += len(line) * 4 * 70 + 600
        length = start + 5_000
        rows = [f'N\t{length}', *(f'L\t{line}' for line in lines)]
        for _ in range(2):
            for start in range(0, length, 20_000):
                rows.append(f'S\t{start}\t{start + 20_000}')
                rows.extend(f'W\t{ms}\t{text}' for text, ms in words if start <= ms - 550 < start + 20_000)
        path = folder / f'{count}.tsv'
        path.write_text('\n'.join(rows) + '\n')
        paths.append(path)
    return paths


def run(ids, timing=None, fake=False):
    sources = [replay.ROOT / name for name in replay.UNTIMED_TIMING] + [Path(__file__).with_name('TimingBenchmark.kt')]
    if timing:
        sources = replay.timing_copied(timing, sources)
    classes = replay.untimed_classes(sources)
    stdlib = next(jar for jar in replay.kotlin_jars() if jar.name.startswith('kotlin-stdlib-'))
    paths = inputs() if fake else [replay.own() / 'untimed' / f'{song}.tsv' for song in ids]
    if not paths:
        raise SystemExit('Give recording IDs, or use --fake for the two generated songs.')
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise SystemExit('Run syncreplay.py untimed first. Missing: ' + ', '.join(missing))
    subprocess.run(replay.replay_container((replay.OUT, replay.OUT, 'ro'), (replay.own(), replay.own(), 'ro'), (stdlib.parent, stdlib.parent, 'ro')) + [
        'java', '-cp', f'{classes}:{stdlib}', 'com.astrovm.crosstune.TimingBenchmarkKt', *map(str, paths)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ids', nargs='*', help='recordings whose TSV inputs already exist')
    parser.add_argument('--fake', action='store_true', help='generate two fake songs with repeated choruses and hearings')
    parser.add_argument('--timing', help='another SungTiming.kt to measure with the same inputs')
    args = parser.parse_args()
    run(args.ids, args.timing, args.fake)
