#!/usr/bin/env python3
"""Replay identical cached inputs against a Git revision and this branch, restoring sources afterward.

Run in a clean production source tree. Keeps both sets of results under the replay cache.
Only production sources change for the baseline; the same replay harness runs on both.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import statistics
import subprocess
from types import SimpleNamespace

import syncreplay as r

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('songs', nargs='+')
parser.add_argument('--before', default='origin/main')
parser.add_argument('--gradle', default='scripts/gradle-box/gradle-box.sh')
parser.add_argument('--rate-song', help='also replay this song at 1.05x with Shazam reporting that rate')
args = parser.parse_args()

def git(*parts):
    return subprocess.check_output(['git', *parts], cwd=r.ROOT).decode().strip()

subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'app/src/main'], cwd=r.ROOT, check=True)
before = git('rev-parse', args.before)
head = git('rev-parse', 'HEAD')
out = r.OUT / 'comparisons' / f'{before[:8]}-{head[:8]}'
out.mkdir(parents=True, exist_ok=True)
paths = git('diff', '--name-only', before, 'HEAD', '--', 'app/src/main').splitlines()
originals = {name: (r.ROOT / name).read_bytes() for name in paths}
baseline = {name: subprocess.check_output(['git', 'show', f'{before}:{name}'], cwd=r.ROOT) for name in paths}
cases = [dict(song=song, **{'from': 0}, work=4500, behind=0, heard='') for song in args.songs]
if args.rate_song:
    cases.append(dict(song=args.rate_song, **{'from': 1}, work=4500, behind=0, heard='', playerSpeed=1.05, skew=0, shazamAhead=0, wordsLength=0, video=True))
results = dict(before=before, after=head, cases=cases)
try:
    for phase, sources in [('before', baseline), ('after', originals)]:
        for name, data in sources.items():
            (r.ROOT / name).write_bytes(data)
        folder = out / phase
        folder.mkdir(exist_ok=True)
        while True:
            (r.OUT / 'replay.json').write_text(json.dumps(dict(songs=cases)))
            (r.OUT / 'replay.songs').write_text('\n'.join(case['song'] for case in cases) + '\n')
            with (folder / 'gradle.log').open('w') as log:
                subprocess.run(shlex.split(args.gradle) + ['-q', ':app:testDebugUnitTest', '--tests', '*SyncReplayTest*', '--rerun'], cwd=r.ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
            def hear(song):
                return r.hear_asked(song, [f"{c['song']}.from{c['from']}.shown.json" for c in cases if c['song'] == song])
            with ThreadPoolExecutor(r.SONGS_HEARD_AT_ONCE) as pool:
                if not sum(pool.map(hear, dict.fromkeys(c['song'] for c in cases))):
                    break
        rows = []
        for case in cases:
            song, start = case['song'], case['from']
            file = r.OUT / f'{song}.from{start}.shown.json'
            shutil.copy2(file, folder / file.name)
            shown = json.loads(file.read_text())
            truth, first = r.sung_lines(song, shown['lines']), r.lit_first(shown)
            lags = [first[k] - truth[k] for k in truth if k in first]
            rows.append(dict(song=song, start=start, lines=len(truth), within03=sum(abs(x) <= .3 for x in lags), within05=sum(abs(x) <= .5 for x in lags), dark=sum(k not in first for k in truth), median=statistics.median(map(abs, lags)) if lags else None, worst=max(lags, key=abs) if lags else None, lyricsSpeed=shown.get('lyricsSpeed')))
            print(phase, rows[-1], flush=True)
            r.score(SimpleNamespace(id=song, start=start, placed=False))
        results[phase] = rows
        (out / 'result.json').write_text(json.dumps(results, indent=2))
finally:
    for name, data in originals.items():
        (r.ROOT / name).write_bytes(data)
    for name in ('replay.json', 'replay.songs'):
        (r.OUT / name).unlink(missing_ok=True)
results['inputHashes'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for song in args.songs for p in r.OUT.glob(song + '.*json*') if not p.name.endswith('shown.json')}
(out / 'result.json').write_text(json.dumps(results, indent=2))
print(out / 'result.json')
