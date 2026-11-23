#!/usr/bin/env python3
"""Measure start-to-start pace of adjacent Japanese or Korean lines in replay truth."""

import argparse
import itertools
import json
import os
from pathlib import Path


def unspaced_letters(text):
    return sum('\u3040' <= c <= '\u30ff' or '\u3400' <= c <= '\u9fff' or '\uac00' <= c <= '\ud7af' for c in text)


def adjacent_paces(lines):
    """Return each adjacent pair's first line and milliseconds per written letter."""
    found = []
    for first, second in itertools.pairwise(lines):
        letters = unspaced_letters(first['text'])
        elapsed = (second['start'] - first['start']) * 1000
        if second['line'] == first['line'] + 1 and letters and elapsed > 0:
            found.append((first['line'], elapsed / letters))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('songs', nargs='+', help='YouTube IDs with prepared truth')
    args = parser.parse_args()
    cache = Path(os.environ['SYNCREPLAY_DIR'])
    for song in args.songs:
        path = cache / f'{song}.truth.jsonl'
        paces = adjacent_paces([json.loads(line) for line in path.read_text().splitlines()])
        fast = [(line, ms) for line, ms in paces if ms <= 200]
        fastest = f'{min(ms for _, ms in paces):.1f} ms/letter' if paces else 'no adjacent pairs'
        print(f'{song}: {len(paces)} pairs, {len(fast)} at 200 ms/letter or faster, fastest {fastest}')
        for line, ms in fast:
            print(f'  lines {line}–{line + 1}: {ms:.1f} ms/letter')


if __name__ == '__main__':
    main()
