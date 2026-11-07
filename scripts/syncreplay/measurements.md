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

## PR #674: the pace and the remaining line errors

Measured on 2026-11-04, on b0bbbe44, with the cached recordings and hearing,
through `GRADLE_BOX_MEMORY=16 GRADLE_BOX_SLOTS=1 nice -n 10 scripts/gradle-box/gradle-box.sh`
passed as the replay's `--gradle` command. No device was used.

`run ZnrSNEah_20 --video --skew 0.0349 --shazam-ahead 45467 --words-length 0`:

| Measured case | Within 0.5 s after the first fix | Median absolute error | Worst error |
|---|---:|---:|---:|
| Without pace, `--skew 0` | 6/13 | 0.78 s | +2.0 s |
| Retained implementation | 7/13 | 0.33 s | -1.0 s |
| Rejected: average the heard anchors' offsets at the known pace | 7/13 | 0.33 s | -1.4 s |
| Target | 13/13 | at most 0.5 s | at most 0.5 s absolute |

The retained replay first makes timing known at 69.045 s. Its first visible placement
already uses the pace: the first two lyric times, 10.20 and 13.14 s, are placed
at 33.710 and 36.551 s, 2.841 s apart rather than 2.940 s. Pace is not applied late.
The score's "first fix" is the *second visible placement*, at 103.345 s, so the
13-line result excludes the three chorus lines at 80.30, 83.90 and 87.56 s.
Eight reference lines before 69 s never light; the phone's separate count is not this replay's count.

After that first fix, the missed reference lines are 14 (+0.9 s), 19 (-1.0 s),
20 (-0.7 s), 22 (+0.6 s), 24 (+0.9 s), and 26 (+0.7 s), rounded to tenths.
The 135.745 s hearing changes extrapolated placement by about 0.55 s.
This suggested carrying the last heard line's noise into the rest of the song.
A regression with balanced noise at both faster and slower paces failed first
(expected 117000 ms, got 116700 ms). The candidate averaged the offsets, but did not improve the recording's score
and worsened its worst error. It was rejected before a green unit-test run;
both the change and its proposed regression were reverted.

The lyric sheet and reference do not agree on one offset at the known pace.
For example, reference lines 14 and 15 are sung at 111.47 and 115.77 s, but their
sheet times are 91.28 and 94.45 s. At `1 / 1.0349`, that sheet interval is 3.063 s,
versus 4.300 s in the reference. Their required offsets differ by 1.237 s:
no fixed offset can put both within 0.5 s. Searching every offset interval endpoint
with hindsight puts at most 10/13 of the later reference lines within 0.5 s.
This is an upper bound for a fixed offset at Shazam's pace, not for individual
line corrections, and is not an offset to put in the app.

There is no evidence here that changing the truth would fix the score. Current
truth and the preserved `.truth.jsonl.cpu` agree within 0.14 s for all 24 common
lines (at most 0.02 s on the three largest pace-only residuals, lines 14, 19, 20).
Both are model estimates, not independent human measurements. The later tiny-model
hearings are also noisy: the 111 s stretch hears "Whenever I get away" and
"I just don't know what to do" for different words in the sheet. The next 136 s
stretch hears only fragments. Better individual line evidence is needed to reach
the target; shifting every later line cannot do it.

No second cached recording is identified as sped up or slowed down in `songs.tsv`
or `set.json`. Faster and slower synthetic timing were considered, but this is
only one real recording with measured Shazam skew. The earlier 15/19 experiment
used a different path and scoring population; it is not evidence of 13/13 on this path.

### Re-measured on the merged head

Measured on 2026-11-04 on bc4e6088 (main at dd8bfa7b merged in), the replay set up as above.
The owner took the 7/13 as better, not fixed: the 0.5 s target stays unmet.

| Measured case | Within 0.5 s | Median absolute error | Worst error | Shown early |
|---|---:|---:|---:|---:|
| `--skew 0` | 6/13 | 0.78 s | +2.0 s | 0/16 |
| `--skew 0.0349` | 7/13 | 0.33 s | -1.0 s | 2/16 |

Both cases still never light 8 lines. All 41 songs, `syncreplay.py all`, this branch against main at the
same base: identical per song and overall, 1223 lines, 301 within 0.3 s, 619 within 0.5 s, 113 dark,
415 shown early. The 41 songs don't pass `--skew`, so they show nothing worse, not an improvement.

The phone check, `phone ZnrSNEah_20 --seconds 200 --app app.morphe.android.apps.youtube.music`, one run on the
Samsung at media volume 1, on the APK built from bc4e6088: 12/23 within 0.5 s, 16/23 within 1 s, median 0.47 s,
1 never lit. On b0bbbe44 it was 10/22, 17/22, median 0.54 s, 2 never lit. The Google YouTube Music build on that
phone pauses whenever another app is in front, so Crosstune hears nothing and the check can't run with it.

### Together with PR #717 (hearing speeds of a video's lead)

A video's speed could be counted twice: PR #717 takes it off the video's clock once three hearings agree, and the
pace of the words took it again. The pace now only applies while the clock isn't taking the speed
(`MainViewModel.leadSpeedTaken`).

The replay can't show this. `run ZnrSNEah_20 --video --skew 0.0349 --shazam-ahead 45467 --words-length 0`, run
on c5a96476, on b2449bdc, and on b2449bdc with the fix switched off (`pace = wordsPace`), gives the same each time:
7/13 within 0.5 s, median 0.33 s, worst -1.0 s, 7/18 shown early, 6 never shown. The replay never reaches the
fix, so what pins it is the two tests in `ListeningTest` (`aVideosSpeedIsntCountedTwiceOnceItIsTakenOffItsClock`,
`aVideoTooFarOffItsSongsSpeedToTakeItOffItsClockKeepsThePaceOfItsWords`), watched failing without it.
An earlier version of this section said 8/13 and 1/18 shown early for the fixed code. That didn't reproduce
on any commit above, so it is dropped.

`all` on b2449bdc: 1223 lines, 301 within 0.3 s, 619 within 0.5 s, 113 dark, 415 shown early. The same as
this branch before the merge (the 41 songs don't pass `--skew`, so this shows nothing got worse).

## The whole set, rebuilt

Measured on 2026-11-07 on 12e05079: `origin/main` at 2505ebcf, whose app code it doesn't change, plus the replay tooling in PR #734.
The agent cache was emptied and every input lost, so all of it is new: recordings, voices, truth, what's heard, and the words found.

`set` then `all`, with truth from two GPU hearings and hearing on the CPU. A second `all` on the same inputs differs by at most 2 lines
on any song and overall: 304 within 0.3 s, 607 within 0.5 s, 104 dark, 411 early. So 2 lines is the noise between two runs.

| Song | Recording | Lines | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---|---:|---:|---:|---:|---:|---:|
| Bizarre Love Triangle | ZnrSNEah_20 | 24 | 6 | 10 | 2 | 5 | -24.1 |
| Every Little Thing She Does Is Magic | 5WEjz2YoH80 | 24 | 8 | 19 | 0 | 5 | -0.7 |
| Expresso Love | 0_RLLNRWur4 | 26 | 6 | 10 | 3 | 13 | -0.9 |
| Otro Como Tú | 2V2nqZjRELg | 38 | 14 | 22 | 0 | 15 | -1.5 |
| Tell It to My Heart | OtXIsUjDOk8 | 32 | 10 | 24 | 0 | 7 | -2.1 |
| Time to Pretend | XUKyV49P8F0 | 16 | 11 | 15 | 0 | 0 | +1.0 |
| Have In Mind (Kalimba Mix) | CH6t8l-jGYI | 17 | 0 | 0 | 17 | 0 | - |
| Francesita | SGnbgk-ZHKU | 1 | 0 | 0 | 0 | 1 | -7.8 |
| Nada Más Que Hablar (En Vivo) | iH8To-d2pX0 | 14 | 0 | 0 | 14 | 0 | - |
| 二人だけのセレモニー | SaeY5sxStGM | 25 | 0 | 0 | 25 | 0 | - |
| Ciclos Banais | 4PgA9No82ik | 39 | 0 | 0 | 39 | 0 | - |
| Stay With Me | ZS6p32gX_yk | 34 | 2 | 13 | 0 | 21 | -1.0 |
| Blue Monday | R1MBI2tSHe0 | 24 | 0 | 0 | 0 | 24 | -1.7 |
| It's My Life | yWQezjGjiqs | 23 | 1 | 1 | 3 | 18 | +5.8 |
| Into the Groove | HjOlnZ8aPdg | 64 | 33 | 53 | 0 | 7 | -0.7 |
| Don't Go | Wg6VS3OL06Q | 11 | 1 | 5 | 0 | 6 | -0.9 |
| Radio Ga Ga | g9L-hOwKcHQ | 37 | 11 | 23 | 0 | 14 | -1.2 |
| Easy Love | VK4PJfTMawc | 7 | 1 | 4 | 0 | 3 | -0.7 |
| カシミヤのほほえみ | K0sXE5_JhMg | 10 | 2 | 3 | 0 | 6 | +1.3 |
| Lose Yourself | 4wOLVrGHiIU | 71 | 18 | 53 | 0 | 18 | -1.1 |
| Bad Guy | ZD6rXLXZOEI | 34 | 23 | 33 | 0 | 0 | +0.5 |
| Smells Like Teen Spirit | ljUtuoFt-8c | 8 | 0 | 0 | 0 | 8 | -2.1 |
| Bohemian Rhapsody | BSTsnWoslP4 | 38 | 11 | 21 | 0 | 12 | +3.4 |
| Hotel California | BciS5krYL80 | 56 | 11 | 36 | 0 | 13 | +1.3 |
| Shape of You | xTvyyoF_LZY | 36 | 7 | 25 | 0 | 11 | -0.9 |
| Blinding Lights | J7p4bzqLvCw | 21 | 11 | 18 | 0 | 2 | +0.7 |
| Take On Me | HzdD8kbDzZA | 20 | 11 | 15 | 0 | 5 | -4.5 |
| Dancing Queen | YkLLcIKhJ64 | 25 | 11 | 18 | 0 | 4 | +1.3 |
| Despacito | FXovf5dsRTw | 57 | 11 | 26 | 0 | 30 | +6.2 |
| De Música Ligera | sWft4cMiwXQ | 19 | 2 | 10 | 0 | 6 | +1.9 |
| Flaca | uEV4RqqnlSE | 42 | 8 | 16 | 0 | 24 | +2.5 |
| Águas de Março | WEuDVcXnElc | 49 | 12 | 23 | 0 | 26 | -1.2 |
| Dynamite | HaEYUJ2aRHs | 24 | 12 | 20 | 0 | 3 | -0.6 |
| Gee | qizghQs4K6E | 23 | 0 | 5 | 1 | 17 | -1.1 |
| Plastic Love | T_lC2O1oIew | 14 | 0 | 0 | 0 | 14 | -11.6 |
| La Vie en rose | qPU8mENUBXk | 24 | 7 | 16 | 0 | 8 | -1.0 |
| 99 Luftballons | AT_0zXw2rRo | 25 | 10 | 13 | 0 | 12 | -1.3 |
| Nel blu dipinto di blu | dXN0jpUUmOw | 13 | 0 | 1 | 0 | 12 | -1.2 |
| Hips Don't Lie | nOj6d-HOw2w | 57 | 7 | 23 | 0 | 33 | -1.0 |
| Every Little Thing She Does Is Magic | XB7AmIsxzfI | 22 | 17 | 21 | 0 | 1 | -0.6 |
| Bizarre Love Triangle | LhLmgg_u29k | 20 | 7 | 10 | 0 | 8 | -1.5 |
| **All** | | **1164** | **302** | **605** | **104** | **412** | -24.1 |

Scored: 1164 of 1779 lines with words.

Against the last full set (`all` on b2449bdc):

| Set | Lines | Within 0.3 s | Within 0.5 s | Dark | Early |
|---|---:|---:|---:|---:|---:|
| Before, Issue #671 | 1223 | 301 (25%) | 619 (51%) | 113 (9%) | 415 |
| Rebuilt | 1164 | 302 (26%) | 605 (52%) | 104 (9%) | 412 |

Close, but not the same set, so a song's change isn't a change in the app. With the old inputs gone, this can't be split further:

- Measured: 59 fewer lines are scored: 22 songs have 93 fewer, 13 have 34 more, 6 are the same.
- Measured: main gained `LyricSpacing.kt` and changes to `LyricsFinder.kt` since aab38b93, so the same lookup can find other words.
- My guess, not measured: the rest is sources answering differently than on 2026-11-03, and Whisper's two hearings agreeing on other lines.

Of the 104 dark, 70 are the 3 songs whose words have no times (`hearSinging` needs lines): Ciclos Banais 39, Have In Mind 17, Nada Más Que Hablar 14.
25 are 二人だけのセレモニー, whose words run 212 s on a 178 s recording, so none are taken: both sheets found for it, LRCLIB's and NetEase's, are that long. It was all dark before too (28 of 28).

### Words looked for without the recording's length

`SyncSetTest` looked words up knowing only the title and artist, while a phone also knows how long what it plays is. Plastic Love got a 477 s sheet on its 308 s recording,
and its 14 lines all went dark, with its truth still scored. Now `set` looks again once the recordings are in. 7 of the 41 songs got other words; Plastic Love got 309 s.
The numbers before that fix, on the same recordings: 1186 lines, 317 within 0.3 s, 615 within 0.5 s, 118 dark, 389 early.

Plastic Love is back to where it was lit: 14 of 14 early, worst -11.6 s, against 15 of 15 and -11.3 s before.

### What it took

| Stage, one song at a time, 30 songs | Median seconds |
|---|---:|
| Demucs on the GPU | 14 |
| Truth, two hearings on the GPU | 143 |
| Hearing 20 s and 8 s stretches, CPU | 36 |
| Whole song | 182 |

`set --at-once 1` kept the load at a median of 3.2 and at most 10.5, with nothing logged about the desktop stalling. Whole set: about 2 hours, and `all` 30 minutes on its first run, 100 s after that.
That first `all` had a median load of 10 and at most 19, with two `cargo` builds from other sessions running at the same time.
CPU times weren't taken again: Demucs at 18 s against 125 s is at the top of this file.

`set --at-once 3` with other agents building took truth from 2 to 16 minutes a song, ROCm warned its queues were oversubscribed, memory ran short, and mr went down at 22:05. No GPU fault or
out-of-memory kill was logged, so what brought it down isn't known. Hearing also ran up to 40 processes in `all` (10 for each of 4 songs); it's now at most 4 for all of them.

Run it again with: `scripts/syncreplay/setup.sh` (`REPLAY_CACHE=FOLDER REPLAY_ROCM=1`), then with `SYNCREPLAY_DIR`, `DEMUCS_PYTHON`, `DEMUCS_DEVICE=cuda` and `WHISPER_PYTHON` set,
`syncreplay.py set --at-once 1 --gradle scripts/gradle-box/gradle-box.sh`, then `all` with the same `--gradle`. Point `HF_HOME`, `TORCH_HOME` and `XDG_CACHE_HOME` at the same folder for the models.
