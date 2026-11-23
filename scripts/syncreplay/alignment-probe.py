#!/usr/bin/env python3
"""Run the app's timed-lyrics alignment on one saved hearing in a bounded JVM.

This narrows an alignment failure to a single hearing. It does not simulate the
app clock, persistence, UI, or later hearings; confirm changes with run/all.
The optional source is a different SungWords.kt, such as the detached main.
"""
import argparse
import base64
import hashlib
import json
import runpy
import subprocess
from pathlib import Path


def encoded(text):
    return base64.b64encode(text.encode()).decode()


def input_rows(found, heard, start, nearest):
    rows = [f'N\t{start}']
    for index, line in enumerate(found['lines']):
        end = line[3] if len(line) > 3 and line[3] is not None else ''
        rows.append(f'L\t{line[0]}\t{encoded(line[1])}\t{end}')
        rows.extend(f'W\t{index}\t{ms}\t{encoded(text)}' for ms, text in line[2])
    rows.extend(f'H\t{ms + nearest - start}\t{encoded(text.strip())}' for ms, text in heard if ms + nearest >= start and text.strip())
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('song')
    parser.add_argument('--from', dest='start', type=int, required=True)
    parser.add_argument('--length', type=int, choices=(8, 20), default=20)
    parser.add_argument('--source', type=Path, help='SungWords.kt to examine')
    parser.add_argument('--host', action='store_true', help='run this small pure-logic probe on the host JVM')
    args = parser.parse_args()
    replay = runpy.run_path(str(Path(__file__).with_name('syncreplay.py')))
    cache = replay['OUT']
    root = replay['ROOT']
    source = (args.source or root / 'app/src/main/java/com/astrovm/crosstune/SungWords.kt').read_text()
    start = source.index('internal object SungAlignment {')
    end = source.index('/** What was heard sung of songs whose words have no timing', start)
    alignment = 'package com.astrovm.crosstune\n\nimport kotlin.math.abs\n\n' + source[start:end]
    digest = hashlib.sha256(alignment.encode()).hexdigest()[:16]
    generated = cache / f'alignment-{digest}.kt'
    generated.write_text(alignment)
    inputs = json.loads((cache / f'{args.song}.found.json').read_text())
    windows = json.loads((cache / f'{args.song}.heard{args.length}.json').read_text())
    nearest = min(map(int, windows), key=lambda at: abs(at - args.start))
    path = cache / f'{args.song}.alignment-input.tsv'
    path.write_text('\n'.join(input_rows(inputs, windows[str(nearest)], args.start, nearest)) + '\n')
    sources = [root / name for name in replay['UNTIMED_TIMING']] + [generated, Path(__file__).with_name('AlignmentProbe.kt')]
    jars = replay['kotlin_jars']()
    stdlib = next(jar for jar in jars if jar.name.startswith('kotlin-stdlib-'))
    if args.host:
        key = replay['kotlin_cache_key'](sources)

        def build(classes):
            subprocess.run(['nice', '-n', '10', 'java', '--enable-native-access=ALL-UNNAMED', '-XX:ActiveProcessorCount=2', '-Xmx2g', '-cp', ':'.join(map(str, jars)),
                            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-cp', str(stdlib),
                            '-d', str(classes), *map(str, sources)], check=True)

        classes = replay['compiled_cache'](cache / 'alignment-host-classes', key, build)
    else:
        classes = replay['untimed_classes'](sources)
    print(f'hearing from {args.start} ms, nearest saved window {nearest} ms', flush=True)
    java = ['java', '-cp', f'{classes}:{stdlib}', 'com.astrovm.crosstune.AlignmentProbeKt', str(path)]
    command = ['nice', '-n', '10', *java] if args.host else replay['replay_container']((cache, cache, 'ro'), (stdlib.parent, stdlib.parent, 'ro')) + java
    subprocess.run(command, check=True)


if __name__ == '__main__':
    main()
