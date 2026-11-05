#!/usr/bin/env python3
"""Time CPU and GPU Demucs on the same recording, with identical random shifts.

Run with the prep environment's Python. Outputs belong in the agent cache.
The model is fetched before timing; the benchmark does not replace prep's vocals.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np
import soundfile as sf
from demucs.pretrained import get_model

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('recording', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
get_model('htdemucs')
results = {}
for device in ('cpu', 'cuda'):
    out = args.output / device
    code = '''import random, numpy, torch
random.seed(0)
numpy.random.seed(0)
torch.manual_seed(0)
from demucs.separate import main
main()
'''
    started = time.monotonic()
    with (args.output / f'{device}.log').open('w') as log:
        done = subprocess.run([sys.executable, '-c', code, '--two-stems=vocals', '-d', device, '-j', '1', '-o', str(out), str(args.recording)], stdout=log, stderr=subprocess.STDOUT,
                              env={**os.environ, 'OMP_NUM_THREADS': '4', 'MKL_NUM_THREADS': '4'})
    results[device] = dict(seconds=time.monotonic() - started, exit=done.returncode)
    (args.output / 'result.json').write_text(json.dumps(results, indent=2))
    print(device, results[device], flush=True)
    if done.returncode:
        sys.exit(f'{device} separation failed; see {args.output / (device + ".log")}')
a, rate_a = sf.read(args.output / 'cpu/htdemucs' / args.recording.stem / 'vocals.wav')
b, rate_b = sf.read(args.output / 'cuda/htdemucs' / args.recording.stem / 'vocals.wav')
if a.shape != b.shape or rate_a != rate_b:
    sys.exit('CPU and GPU produced different sample counts or sample rates')
results['comparison'] = dict(samples=len(a), rate=rate_a, correlation=float(np.corrcoef(a.ravel(), b.ravel())[0, 1]),
                             snr_db=float(10 * np.log10(np.sum(a * a) / np.sum((a - b) ** 2))))
(args.output / 'result.json').write_text(json.dumps(results, indent=2))
print(results['comparison'])
