# Prep measurements

Measured on 2026-11-03 on a shared PC with 6 cores (12 threads) and 31 GB with an RX 6800 (gfx1030, 16 GB).
Other agents were building, so these are elapsed times under that load, not isolated throughput.
Raw logs and comparisons stay in `build/syncreplay/bench/`.

| Phase | CPU seconds | GPU seconds | Decision |
|---|---:|---:|---|
| Demucs, full LhLmgg_u29k | 124.79 | 18.33 | Use ROCm when explicitly configured, 6.8 times faster |
| Truth, full LhLmgg_u29k | 346.82 | 34.40 | Keep CPU; Vulkan changes the reference |
| Hearing, 24 existing 8-second windows of 5WEjz2YoH80 | 32.2 | 9.3 | Keep CPU; Vulkan changes words and timestamps |
| CPU hearing, full LhLmgg_u29k, 20-second windows | 512.77 | | Keep CPU |
| CPU hearing, full LhLmgg_u29k, 8-second windows | 189.20 | | Keep CPU |

ROCm: existing `~/.cache/crosstune-syncreplay-gpu/bin/python`, torch 2.14.1+rocm7.2.
CPU: existing `~/.cache/crosstune-syncreplay-venv/bin/python`, torch 2.15.0+cu132 with no CUDA device.
`torch.cuda.is_available()` was true in the ROCm environment; GPU activity reached 92% during separation.
No new Python environment or ROCm container was needed.

Full-song CPU and GPU vocal files had identical sample counts (23,108,400), correlation 0.9926209,
and difference SNR 18.3135 dB. Demucs random shifts were unseeded, so this is a signal comparison,
not proof of numerical equivalence. Fresh CPU separation plus transcription also differed from the
saved reference: 20 common lines, median 0.02 s, worst 1.22 s. The saved truth was preserved.

The initial short-window benchmark was stopped when available RAM approached 4 GB, freeing memory
for prep and the other agents. It was measured after RAM recovered. Summing these phase measurements
gives 1173.58 s on CPU versus 1067.12 s with GPU separation (9.1% less). This is a sum of phases
measured under changing load, not a measured end-to-end speedup.

## Vulkan checks

The first build failed on missing `spirv/unified1/spirv.hpp`. Rebuilding with
`-DCMAKE_CXX_FLAGS=-I/home/linuxbrew/.linuxbrew/include` succeeded.
An initial CLI invocation rejected the obsolete `-nc` option; the actual comparison used
`-mc 0 -nfa -dtw medium -bs 5 -t 2 -ojf` on the existing CPU-separated vocals.
Token DTW times were passed through the same line matcher and voice-onset adjustment as CPU truth.

| Recording | GPU seconds | Saved / candidate / common lines | Median difference | Worst difference |
|---|---:|---:|---:|---:|
| 0_RLLNRWur4 | 74.47 | 40 / 40 / 33 | 0.26 s | 0.88 s |
| LhLmgg_u29k | 34.40 | 21 / 23 / 20 | 0.345 s | 1.30 s |
| XB7AmIsxzfI | 109.62 | 15 / 21 / 12 | 0.415 s | 36.31 s |
| ZnrSNEah_20 | 38.91 | 24 / 24 / 23 | 0.33 s | 0.81 s |
| Combined | | 100 / 108 / 88 | 0.30 s | 36.31 s |

Vulkan hearing took 19.6 s with one worker, 9.3 s with three; CPU with six workers took 32.2 s.
Only 15/24 windows had the same words and 6/24 identical words and timestamps. The worst timestamp
change was 2360 ms. GPU hearing and GPU truth were both rejected; their outputs never replaced prep data.

## Interruption and resuming

The previous session records an explicit user request to stop everything at 2026-11-03T23:30:16Z.
There is no evidence that the final prep process crashed. There was a resume bug: `set` skipped a
song once truth existed, even when hearing was incomplete. The regression fails against 06dbca73
because prep is never called, and passes after the fix.

`set --cached` resumes the stored set. Truth is preserved; each heard window is saved atomically;
completion requires both hearing lengths and every expected window. Separation publishes vocals only
once Demucs exits successfully. An individual subprocess failure is named, remaining songs are tried,
and the command exits unsuccessfully with all failed IDs.

Optional ROCm separation:

```sh
DEMUCS_PYTHON="$HOME/.cache/crosstune-syncreplay-gpu/bin/python" DEMUCS_DEVICE=cuda \
  nice -n 10 "$HOME/.cache/crosstune-syncreplay-venv/bin/python" scripts/syncreplay/syncreplay.py set --cached
```

Without those environment variables, separation and truth use the CPU. Hearing always uses the CPU.

The missing Japanese row had the wrong artist: カシミヤのほほえみ is by 八神純子,
[as credited in the published lyrics](https://www.uta-net.com/song/96712/), not 国分友里恵.

## Truth on the GPU with PyTorch

Whisper medium through PyTorch on ROCm (`WHISPER_PYTHON`), which unlike Vulkan keeps the CPU's times.
One hearing isn't enough: Whisper retries a stretch it can't make out by sampling at random, so hearings differ.
Four hearings of XB7AmIsxzfI, all with the same settings, heard 214, 294, 276 and 277 words. The one with 214 lost
the chorus at 120 s and matched that line to the next one, at 161.78 s, 41.21 s off with nothing to show for it.
So the GPU hears each song twice and keeps only the lines both time within 0.5 s (`agreed`).

Against the saved CPU truth, under a load of about 25 on 6 cores (12 threads):

| Recording | Two GPU hearings, seconds | CPU / GPU / common lines | Median difference | Worst difference | Within 0.3 s |
|---|---:|---:|---:|---:|---:|
| LhLmgg_u29k | 378.8 | 21 / 21 / 21 | 0.000 | 1.22 | 17 |
| XB7AmIsxzfI | 473.6 | 15 / 17 / 11 | 0.010 | 0.56 | 10 |
| 0_RLLNRWur4 | 427.3 | 40 / 36 / 35 | 0.000 | 0.78 | 34 |
| ZnrSNEah_20 | 166.4 | 24 / 24 / 24 | 0.000 | 0.14 | 24 |
| All | | 91 common | 0.000 | 1.22 | 85 |

The CPU's own repeat run on LhLmgg_u29k differed by a median of 0.02 s and at worst 1.22 s, so this is as close as
the CPU is to itself. CPU truth on the same machine in the same hour took 174 to 780 s for one hearing. The GPU was
about 10% busy, as the CPU work around it was waiting its turn, so these times say more about the load than the GPU.

## Hearing less, or on the GPU

Replays of 23 songs, 581 scored lines, with an unchanged app:

| What was heard | Within 0.3 s | Within 0.5 s | Dark | Lit lines that moved over 0.5 s |
|---|---:|---:|---:|---:|
| A stretch every second, run 1 | 145 | 291 | 86 | |
| A stretch every second, run 2 | 143 | 286 | 86 | 0 |
| A stretch every 5 seconds | 133 | 274 | 82 | 62 |

So a coarser grid costs about 2 points, more than two runs differ, and prep stays at every second.

On the GPU, Vulkan's words don't stand in for the CPU's. One 20-second stretch took 1.2 to 2.3 s, against 6.3 to 6.9 s on the CPU,
but 3, 6 and 10 at once heard only 0.8, 0.6 and 0.8 stretches a second with other GPU work running, against about 1.3 for 10 on the CPU.
Replayed with the GPU's words for each stretch asked, against the CPU's: 139 lines within 0.3 s rather than 145, 281 within 0.5 s rather
than 291, 91 dark rather than 86, 50 of 857 lit lines moved over 0.5 s, and 64 of 231 stretches had the same words. On Bad Luck, the only
song compared, the replay on the CPU is a median 0.02 s from the phone, at worst 0.1 s. So hearing stays on the CPU.

## Hearing only what a replay asks for

The same 23 songs, starting from a stretch every 20 s, then hearing what each replay asked for, against every second:

| Round | New stretches | Stretches so far, of 12,054 | Lines lit in both | Lit in one only | Worst difference |
|---:|---:|---:|---:|---:|---:|
| 1 | 192 | 860 | 844 | 42 | 23.2 s |
| 2 | 30 | 890 | 868 | 10 | 2.1 s |
| 3 | 9 | 899 | 868 | 0 | 1.1 s |
| 4 | 0 | 899 | 868 | 0 | 0.1 s |

Settled, it's as close to every second as two runs of every second are to each other (868 lit in both, worst 0.1 s),
from 7.5% of the stretches. So prep hears every 20 s, and replays hear the rest. Each round was 73 to 101 s under load.

## The whole set

The 41 songs prepped, each from its start, with truth from two GPU hearings and what's heard asked for by the replay. Run with `all`, on whisper.cpp a46fadb3.

| Song | Lines | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---:|---:|---:|---:|---:|---:|
| Expresso Love | 36 | 13 | 21 | 4 | 9 | +2.1 |
| Otro Como Tú | 38 | 16 | 24 | 0 | 14 | -1.1 |
| Ciclos Banais | 41 | 0 | 0 | 41 | 0 | - |
| Lose Yourself | 74 | 9 | 40 | 0 | 34 | -1.1 |
| Every Little Thing She Does Is Magic | 26 | 21 | 24 | 0 | 0 | +10.9 |
| 99 Luftballons | 24 | 10 | 15 | 0 | 9 | -1.1 |
| Bohemian Rhapsody | 46 | 24 | 35 | 0 | 9 | -1.6 |
| Hotel California | 54 | 12 | 29 | 0 | 14 | +4.0 |
| Have In Mind (Kalimba Mix) | 17 | 0 | 0 | 17 | 0 | - |
| Despacito | 55 | 9 | 25 | 0 | 29 | +6.2 |
| Dynamite | 23 | 11 | 19 | 0 | 4 | -1.0 |
| Into the Groove | 63 | 15 | 39 | 0 | 0 | +1.5 |
| Take On Me | 16 | 5 | 14 | 0 | 2 | -1.3 |
| Blinding Lights | 26 | 14 | 23 | 0 | 3 | -0.6 |
| カシミヤのほほえみ | 14 | 1 | 3 | 0 | 10 | -0.8 |
| LhLmgg_u29k | 21 | 6 | 9 | 0 | 10 | -1.1 |
| Tell It to My Heart | 38 | 8 | 19 | 0 | 18 | -2.2 |
| Blue Monday | 22 | 1 | 3 | 0 | 19 | -2.3 |
| Francesita | 4 | 1 | 1 | 2 | 1 | -2.8 |
| 二人だけのセレモニー | 28 | 0 | 0 | 28 | 0 | - |
| Plastic Love | 15 | 0 | 0 | 0 | 15 | -11.3 |
| Easy Love | 12 | 1 | 3 | 0 | 9 | -0.8 |
| Águas de Março | 47 | 12 | 23 | 0 | 24 | -1.2 |
| Don't Go | 19 | 2 | 14 | 0 | 4 | +10.3 |
| XB7AmIsxzfI | 17 | 10 | 16 | 0 | 1 | -0.6 |
| Time to Pretend | 25 | 3 | 10 | 0 | 14 | -1.3 |
| Dancing Queen | 26 | 13 | 24 | 0 | 0 | +1.3 |
| Bad Guy | 33 | 4 | 30 | 0 | 3 | -0.7 |
| Stay With Me | 34 | 2 | 12 | 0 | 22 | -1.0 |
| Bizarre Love Triangle | 24 | 6 | 10 | 2 | 5 | -24.1 |
| Nel blu dipinto di blu | 12 | 0 | 0 | 0 | 12 | -1.2 |
| Radio Ga Ga | 31 | 20 | 22 | 0 | 6 | -1.6 |
| Nada Más Que Hablar (En Vivo) | 17 | 0 | 0 | 17 | 0 | - |
| Smells Like Teen Spirit | 9 | 0 | 0 | 0 | 8 | +2.3 |
| Hips Don't Lie | 59 | 13 | 25 | 4 | 29 | -48.8 |
| La Vie en rose | 28 | 11 | 20 | 0 | 3 | +1.8 |
| Gee | 29 | 1 | 8 | 0 | 21 | -1.1 |
| De Música Ligera | 13 | 6 | 6 | 0 | 0 | +6.7 |
| Flaca | 42 | 10 | 17 | 0 | 24 | +2.8 |
| Shape of You | 42 | 8 | 28 | 0 | 14 | -0.8 |
| It's My Life | 23 | 2 | 3 | 2 | 16 | +5.3 |
| **All** | **1223** | **300** | **614** | **117** | **415** | |

Scored: 1223 of 1799 lines with words: 25% within 0.3 s, 50% within 0.5 s, 117 dark.

Ciclos Banais, Have In Mind and Nada Más Que Hablar found words with no times at all, and the app only hears timed words (`hearSinging`, which needs lines), so all their lines are dark: 75 of the 117. That's what the app does today, not a fault of the replay.

## Every writing

Truth used to read only Latin letters, so the 5 Japanese songs scored 5 lines between them and Gee 11. `pieces` now matches every writing, one letter at a time where it's written without spaces.

| Song | Lines with words | Truth before | Truth now |
|---|---:|---:|---:|
| Gee | 72 | 11 | 29 |
| Stay With Me | 51 | 3 | 34 |
| カシミヤのほほえみ | 31 | 0 | 14 |
| Plastic Love | 26 | 0 | 15 |
| Easy Love | 33 | 0 | 12 |
| 二人だけのセレモニー | 38 | 2 | 28 |
| All | 251 | 16 | 132 |

Of the 11,191 words not in these writings across the set, 3 match differently, all for the better: Whisper and the lyrics both write `großer`, which used to lose its `ß`.

The rest stay unscored. My guess, not measured: Whisper writes a word in kana where the lyrics use kanji, or the reverse.

One of the 6 hearings on the GPU died with a memory access fault, on Gee. It didn't happen again when run again, and `set --cached` resumes after it, so I left it at that.


### The words the app really shows

That table replays the words given to prep. `set` now also keeps the words the app finds (`ID.found.json`), and a replay plays those, as the phone does. Each line is held to its truth by its words, so fewer lines are scored where another sheet breaks them differently. No app change between these, same 41 songs, whisper.cpp a46fadb3, with the truth PR #708 reads:

| Words replayed | Lines | Within 0.3 s | Within 0.5 s | Dark | Early |
|---|---:|---:|---:|---:|---:|
| As given to prep | 1114 | 296 | 590 | 92 | 355 |
| As the app finds them | 1083 | 319 | 638 | 90 | 295 |

## Limits of the timing table

Truth is an estimated reference, not a human audit of every lyric. Unmatched lines are excluded, and the overall
table prints that coverage explicitly. Issue #662's target is every line within 0.3 s, none dark. Passing scores on
matched lines alone cannot establish that target for all sung lines. No app timing behavior is changed in this PR.
