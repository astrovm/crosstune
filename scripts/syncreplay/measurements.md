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

Measured on 2026-11-07 on 77bc5168: `origin/main` at a3bbf54f, whose app code it doesn't change, plus the replay tooling in PR #734.
The agent cache was emptied and every input lost, so all of it is new: recordings, voices, truth, what's heard, and the words found.

`set` then `all`, with truth from two GPU hearings and hearing on the CPU. 43 songs are in `songs.tsv`, and 42 are scored: Bring Me To Life (Cyberpunk Shamisen Cover) has no words to find,
so `set` skips it. The More I See You and it are new since Issue #671.

| Song | Recording | Lines | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---|---:|---:|---:|---:|---:|---:|
| Bizarre Love Triangle | ZnrSNEah_20 | 24 | 6 | 10 | 2 | 5 | -24.1 |
| Every Little Thing She Does Is Magic | 5WEjz2YoH80 | 24 | 8 | 19 | 0 | 5 | -0.7 |
| Expresso Love | 0_RLLNRWur4 | 26 | 6 | 10 | 3 | 13 | -0.9 |
| Otro Como Tú | 2V2nqZjRELg | 38 | 16 | 24 | 0 | 14 | -1.6 |
| Tell It to My Heart | OtXIsUjDOk8 | 32 | 10 | 24 | 0 | 7 | -2.1 |
| Time to Pretend | XUKyV49P8F0 | 16 | 11 | 15 | 0 | 0 | +1.0 |
| Have In Mind (Kalimba Mix) | CH6t8l-jGYI | 17 | 0 | 0 | 17 | 0 | - |
| Francesita | SGnbgk-ZHKU | 1 | 0 | 0 | 0 | 1 | -7.8 |
| Nada Más Que Hablar (En Vivo) | iH8To-d2pX0 | 14 | 0 | 0 | 14 | 0 | - |
| 二人だけのセレモニー | SaeY5sxStGM | 25 | 0 | 0 | 25 | 0 | - |
| Ciclos Banais | 4PgA9No82ik | 39 | 0 | 0 | 39 | 0 | - |
| The More I See You | 8rXamE-pYw0 | 8 | 0 | 0 | 8 | 0 | - |
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
| Bohemian Rhapsody | BSTsnWoslP4 | 45 | 23 | 30 | 0 | 11 | +3.0 |
| Hotel California | BciS5krYL80 | 56 | 11 | 36 | 0 | 13 | +1.3 |
| Shape of You | xTvyyoF_LZY | 36 | 7 | 25 | 0 | 11 | -0.9 |
| Blinding Lights | J7p4bzqLvCw | 21 | 11 | 18 | 0 | 2 | +0.7 |
| Take On Me | HzdD8kbDzZA | 20 | 11 | 15 | 0 | 5 | -4.5 |
| Dancing Queen | YkLLcIKhJ64 | 25 | 11 | 18 | 0 | 4 | +1.3 |
| Despacito | FXovf5dsRTw | 57 | 11 | 26 | 0 | 30 | +6.2 |
| De Música Ligera | sWft4cMiwXQ | 19 | 2 | 10 | 0 | 6 | +1.9 |
| Flaca | uEV4RqqnlSE | 42 | 9 | 16 | 0 | 24 | +2.5 |
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
| **All** | | **1179** | **317** | **616** | **112** | **410** | -24.1 |

Scored: 1179 of 1780 lines with words. A second `all` on the same inputs: 315 within 0.3 s, 617 within 0.5 s, 112 dark, 409 early. So the noise between two runs is about 2 lines, and at most 2 on any one song (Otro Como Tú).

Against the last full set (`all` on b2449bdc):

| Set | Lines | Within 0.3 s | Within 0.5 s | Dark | Early |
|---|---:|---:|---:|---:|---:|
| Before, Issue #671 | 1223 | 301 (25%) | 619 (51%) | 113 (9%) | 415 |
| Rebuilt, the same 41 songs | 1171 | 317 (27%) | 616 (53%) | 104 (9%) | 410 |
| Rebuilt, with The More I See You | 1179 | 317 (27%) | 616 (52%) | 112 (10%) | 410 |

Close, but not the same inputs, so a song's change isn't a change in the app. With the old inputs gone, this can't be split further:

- Measured: on the 41, 52 fewer lines are scored: 22 songs have 86 fewer, 13 have 34 more, 6 are the same.
- Measured: main gained `LyricSpacing.kt` and changes to `LyricsFinder.kt` since aab38b93, so the same lookup can find other words.
- My guess, not measured: the rest is sources answering differently than on 2026-11-03, and Whisper's two hearings agreeing on other lines.

Of the 112 dark, 78 are the 4 songs whose words have no times (`hearSinging` needs lines): Ciclos Banais 39, Have In Mind 17, Nada Más Que Hablar 14, The More I See You 8.
25 are 二人だけのセレモニー, whose words run 212 s on a 178 s recording, so none are taken: both sheets found for it, LRCLIB's and NetEase's, are that long. It was all dark before too (28 of 28).

### Keeping the numbers comparable

Sources answer differently from one lookup to the next: between two `set` runs minutes apart, Bohemian Rhapsody went from 38 scored lines to 45, with 21 within 0.5 s to 30, and no other song moved by more than the noise.
So `set` without `--cached` changes the baseline. Compare with `all` on the words kept, and take a new baseline whenever they're found again.

### Words looked for without the recording's length

`SyncSetTest` looked words up knowing only the title and artist, while a phone also knows how long what it plays is. Plastic Love got a 477 s sheet on its 308 s recording,
and its 14 lines all went dark, with its truth still scored. Now `set` looks again once the recordings are in. 7 of the 41 songs got other words; Plastic Love got 309 s.
The numbers before that fix, on the same recordings: 1186 lines, 317 within 0.3 s, 615 within 0.5 s, 118 dark, 389 early.

Plastic Love is back to where it was lit: 14 of 14 early, worst -11.6 s, against 15 of 15 and -11.3 s before.

### What it took

| Stage, one song at a time, 30 songs | Median seconds |
|---|---:|
| Demucs on the GPU | 13 |
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

## Counting player speed once, and singing without spaces (PR #728)

Measured on 2026-11-07, with the set above, never looked up again. Main at 57bd6bfc gave the same as that section, line for line in the totals: 1179, 317, 616, 112, 410, so PR #742 moved nothing here.

### The baseline from here on: the sheet's own length

The replay now tells the app how long the sheet it found is (`length` in `ID.found.json`), as the phone does, and no longer that every sheet is as long as the recording (Issue #689). The app's own hearing stays unset: a stand-in Shazam put the Police's first while at 22.8 s, where the phone puts it at 11.4 to 11.6 s and the replay without it at 11 s. Measured on main 7e8e88f7 and this branch merged on it (ea572d9c), both with this harness.

| `all` | Lines | Within 0.3 s | Within 0.5 s | Dark | Early |
|---|---:|---:|---:|---:|---:|
| Main, old harness | 1179 | 317 | 616 | 112 | 410 |
| Main, sheet's own length | 1168 | 314 | 612 | 85 | 417 |
| This branch, sheet's own length | 1168 | 311 | 624 | 85 | 407 |

Main, old harness to the sheet's own length, the songs that moved:

| Song | Recording | Lines | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---|---:|---:|---:|---:|---:|---:|
| 二人だけのセレモニー | SaeY5sxStGM | 25 → 14 | 0 → 0 | 0 → 0 | 25 → 1 | 0 → 0 | none → +11.1 |
| It's My Life | yWQezjGjiqs | 23 | 1 → 1 | 1 → 1 | 3 → 0 | 18 → 22 | +5.8 → -2.7 |
| Otro Como Tú | 2V2nqZjRELg | 38 | 16 → 16 | 24 → 23 | 0 → 0 | 14 → 15 | -1.6 → -1.6 |
| Every Little Thing She Does Is Magic | 5WEjz2YoH80 | 24 | 8 → 7 | 19 → 18 | 0 → 0 | 5 → 5 | -0.7 → -0.7 |
| Easy Love | VK4PJfTMawc | 7 | 1 → 1 | 4 → 3 | 0 → 0 | 3 → 4 | -0.7 → -0.7 |
| Bad Guy | ZD6rXLXZOEI | 34 | 23 → 22 | 33 → 33 | 0 → 0 | 0 → 0 | +0.5 → +0.5 |
| Shape of You | xTvyyoF_LZY | 36 | 7 → 6 | 25 → 24 | 0 → 0 | 11 → 12 | -0.9 → -0.9 |

- The 11 lines no longer scored are all 二人だけのセレモニー's. Told the sheet is as long as the 178.2 s recording, its last two lines, at up to 184.5 s, run past that end, so the app didn't take it (the 212.6 s sheet is from a longer release). It showed no lines, and the scoring fell back to prep's 38 lines, all dark. Told the sheet's own length, it shows the sheet's 24 lines, which join 11 pairs of prep's lines into one ("ねぇ 3本きりの花束でも ときめいてる"). The second of each pair has no line of its own to light, so it has no time to score. The phone scores the same way, by the lines the app shows.
- It's My Life loses its 3 dark lines on main and on this branch, so its +14.4 s regression below is gone with this harness: -2.7 s on both.

This branch against main, both with the sheet's own length, the songs that moved:

| Song | Recording | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---|---:|---:|---:|---:|---:|
| Otro Como Tú | 2V2nqZjRELg | 16 → 16 | 23 → 24 | 0 → 0 | 15 → 14 | -1.6 → -1.6 |
| Every Little Thing She Does Is Magic | 5WEjz2YoH80 | 7 → 8 | 18 → 19 | 0 → 0 | 5 → 5 | -0.7 → -0.7 |
| 99 Luftballons | AT_0zXw2rRo | 10 → 6 | 13 → 18 | 0 → 0 | 12 → 7 | -1.3 → -1.3 |
| Hotel California | BciS5krYL80 | 11 → 11 | 36 → 35 | 0 → 0 | 13 → 14 | +1.3 → +1.3 |
| Despacito | FXovf5dsRTw | 11 → 14 | 26 → 29 | 0 → 0 | 30 → 27 | +6.2 → +6.2 |
| Dynamite | HaEYUJ2aRHs | 12 → 10 | 20 → 19 | 0 → 0 | 3 → 4 | -0.6 → -0.9 |
| Tell It to My Heart | OtXIsUjDOk8 | 10 → 11 | 24 → 24 | 0 → 0 | 7 → 7 | -2.1 → -2.1 |
| Plastic Love | T_lC2O1oIew | 0 → 0 | 0 → 1 | 0 → 0 | 14 → 13 | -11.6 → -11.1 |
| Easy Love | VK4PJfTMawc | 1 → 0 | 3 → 6 | 0 → 0 | 4 → 1 | -0.7 → -0.5 |
| Time to Pretend | XUKyV49P8F0 | 11 → 11 | 15 → 15 | 0 → 0 | 0 → 0 | +1.0 → +1.1 |
| Bizarre Love Triangle | ZnrSNEah_20 | 6 → 3 | 10 → 8 | 2 → 2 | 5 → 7 | -24.1 → -24.1 |
| Nel blu dipinto di blu | dXN0jpUUmOw | 0 → 2 | 1 → 3 | 0 → 0 | 12 → 10 | -1.2 → -1.0 |
| Hips Don't Lie | nOj6d-HOw2w | 7 → 7 | 23 → 24 | 0 → 0 | 33 → 33 | -1.0 → -1.1 |
| Shape of You | xTvyyoF_LZY | 6 → 6 | 24 → 23 | 0 → 0 | 12 → 13 | -0.9 → -0.9 |

Worse here: 99 Luftballons, Bizarre Love Triangle, Dynamite, Hotel California and Shape of You, for the reasons below, which this harness doesn't change. It's My Life no longer is.

`run ID --like-phone` again, now told the sheet's own length too, against the same phone runs:

| Phone run | Median apart, before | Median apart, now | Worst, before | Worst, now |
|---|---:|---:|---:|---:|
| Police run 1 | 0.57 s | 0.79 s | 1.2 s | 1.5 s |
| Police run 2 | 0.49 s | 0.47 s | 2.4 s | 2.4 s |
| Expresso Love run 2 | 0.03 s | 0.03 s | 2.0 s | 2.0 s |

Not closer. `--like-phone` already took the sheet's length from the phone's log, so only the length NetEase's word-by-word answer gives changed. What keeps Police run 1 apart is a drift: the phone lit each line a little later than the replay, 0.2 s at 12 s and 1.5 s at 142 s (1.2 s before this change). Its hearings put Shazam's speed at 0.96 to 1.01, about the 0.985 a 261 s sheet on a 265 s video means. Why the phone's words go slower than the replay's isn't known yet (Issue #722).

### Before: main's harness, as it was

| `all` | Lines | Within 0.3 s | Within 0.5 s | Dark | Early |
|---|---:|---:|---:|---:|---:|
| Main | 1179 | 317 | 616 | 112 | 410 |
| This branch | 1179 | 311 | 623 | 111 | 405 |
| This branch again | 1179 | 312 | 624 | 111 | 404 |

The songs that moved, of 42. The repeat moved at most 1 line on a song.

| Song | Recording | Within 0.3 s | Within 0.5 s | Dark | Early | Worst |
|---|---|---:|---:|---:|---:|---:|
| 99 Luftballons | AT_0zXw2rRo | 10 → 6 | 13 → 18 | 0 → 0 | 12 → 7 | -1.3 → -1.3 |
| Hotel California | BciS5krYL80 | 11 → 11 | 36 → 35 | 0 → 0 | 13 → 14 | +1.3 → +1.3 |
| Despacito | FXovf5dsRTw | 11 → 14 | 26 → 29 | 0 → 0 | 30 → 27 | +6.2 → +6.2 |
| Dynamite | HaEYUJ2aRHs | 12 → 10 | 20 → 18 | 0 → 0 | 3 → 5 | -0.6 → -0.9 |
| Take On Me | HzdD8kbDzZA | 11 → 10 | 15 → 15 | 0 → 0 | 5 → 5 | -4.5 → -4.5 |
| Tell It to My Heart | OtXIsUjDOk8 | 10 → 11 | 24 → 24 | 0 → 0 | 7 → 7 | -2.1 → -2.1 |
| Plastic Love | T_lC2O1oIew | 0 → 0 | 0 → 1 | 0 → 0 | 14 → 13 | -11.6 → -11.1 |
| Easy Love | VK4PJfTMawc | 1 → 0 | 4 → 6 | 0 → 0 | 3 → 1 | -0.7 → -0.5 |
| Time to Pretend | XUKyV49P8F0 | 11 → 11 | 15 → 15 | 0 → 0 | 0 → 0 | +1.0 → +1.1 |
| Bizarre Love Triangle | ZnrSNEah_20 | 6 → 3 | 10 → 8 | 2 → 2 | 5 → 7 | -24.1 → -24.1 |
| Nel blu dipinto di blu | dXN0jpUUmOw | 0 → 2 | 1 → 3 | 0 → 0 | 12 → 10 | -1.2 → -1.0 |
| Hips Don't Lie | nOj6d-HOw2w | 7 → 7 | 23 → 24 | 0 → 0 | 33 → 33 | -1.0 → -1.1 |
| Gee | qizghQs4K6E | 0 → 0 | 5 → 5 | 1 → 1 | 17 → 17 | -1.1 → -1.0 |
| Shape of You | xTvyyoF_LZY | 7 → 6 | 25 → 23 | 0 → 0 | 11 → 13 | -0.9 → -0.9 |
| It's My Life | yWQezjGjiqs | 1 → 1 | 1 → 1 | 3 → 2 | 18 → 19 | +5.8 → +14.4 |

Each part off, to see which moved what, all else as this branch:

| `all` | Within 0.3 s | Within 0.5 s | Dark | Early | Moved most |
|---|---:|---:|---:|---:|---|
| No line heard alone (`canPlaceAlone` in `untilSung`) | 316 | 621 | 112 | 406 | Easy Love 4 → 1 within 0.5 s, worst -10.1 s, against 6 with it |
| No shift from a distinct line (`distinctLineShift`) | 320 | 619 | 111 | 408 | 99 Luftballons 16 within 0.3 s, against 6 with it |

Worse, and why:

- 99 Luftballons, 10 → 6 within 0.3 s, but 13 → 18 within 0.5 s. A distinct line puts the words right 12 s sooner. They end up as near the singing as with that part off, a mean of 0.18 s off against 0.20 s, but about 0.25 s earlier. Lines light 0.3 s early on purpose, so more of them land just past 0.3 s.
- Bizarre Love Triangle (ZnrSNEah_20), 6 → 3 and 10 → 8. A line heard alone moves every later while: it hears from 57.7 s where main hears from 79.3 s, and the whiles that follow catch lines 14 to 19 less well, +1.5 s against +0.6 s.
- It's My Life, worst +5.8 → +14.4 s, with one dark line fewer. Also from a line heard alone: the first seconds of a while at 65 s are heard as music, so the words stop being shown for 24.5 s, against 20.5 s from 60 s on main. One line then lights as they come back. That stop is main's, on any while opening without words.
- Dynamite (20 → 18, 19 on the repeat) and Shape of You (25 → 23) are at the edge of the noise.

### Plastic Love

| T_lC2O1oIew | Within 0.5 s | Early | Median off, once put right | Put right at |
|---|---:|---:|---:|---:|
| Main | 0 of 14 | 14 | 10.86 s | never |
| This branch | 1 of 14 | 13 | 0.98 s | 78.1 s |

Better, not fixed. On main no while heard is ever matched, as the words have no spaces and the singing is split elsewhere. Here the second is, the one from 53.5 s, and the whole sheet moves about 10.4 s, at 78.1 s. Lines 0 to 5 are lit before that, so they stay about 11 s early. After it, the sheet is within 0.5 s for 9 of its 14 lines and within 1.1 s for all, and the later lines drift to about -1 s. Nothing here corrects that drift, so the line-by-line target stays open.

### A player rate of 1.05x

`run XB7AmIsxzfI --from 1 --player-speed 1.05 --skew 0 --shazam-ahead 0 --words-length 0 --video`, the same harness on both. This tests the clock's sums on cached hearing, not hearing stretched audio. Told the sheet's own length, both give exactly the same lines.

| | Lyrics clock | Within 0.3 s | Within 0.5 s | Worst |
|---|---:|---:|---:|---:|
| Main | 1.1024998x | 0 of 22 | 1 of 22 | -6.6 s |
| This branch | 1.05x | 21 of 22 | 22 of 22 | -0.5 s |

### What the replay gets told

This branch first replayed with main's harness, which told the app a sheet is as long as the recording. With the sheet's own length and a stand-in Shazam together, main gave 1168 lines, 311, 614, 90 dark, 407, and this branch 306, 619, 87, 406. Hearing then started later on some songs, as on XB7AmIsxzfI, from 22.8 s instead of 11 s, so the stand-in moved the replay away from the phone. The sheet's own length alone is the baseline now, at the top of this section.

### On the phone

The Samsung at media volume 1, put back to 0 after each run, `phone ID --seconds 150 --app app.morphe.android.apps.youtube.music`. This branch's APK (ea572d9c) and main's (7e8e88f7), each with what it learnt forgotten before every run. Within 0.5 s, within 1 s, and the median off:

| Song | Run | This PR | Main |
|---|---:|---|---|
| Every Little Thing She Does Is Magic, XB7AmIsxzfI | 1 | 13/22, 22/22, 0.41 s | 19/21, 21/21, 0.16 s |
| | 2 | 6/21, 12/21, 0.97 s | 19/21, 21/21, 0.18 s |
| | 3 | 20/22, 22/22, 0.23 s | 9/22, 17/22, 0.57 s |
| Expresso Love, 0_RLLNRWur4 | 1 | 12/17, 14/17, 0.18 s | 15/18, 18/18, 0.19 s |
| | 2 | 16/18, 18/18, 0.33 s | 16/17, 17/17, 0.22 s |
| | 3 | 12/17, 17/17, 0.38 s | 15/17, 17/17, 0.31 s |

- Measured: no sung line goes dark in any run, on either build.
- Late runs happen on both builds. This PR's Police run 2 has lines 5 to 19 up to +2.1 s late until about 105 s. Main's run 3 has lines 12 to 29 +1.0 to +1.4 s late. Expresso Love's run 1 on this PR has three lines at +5.2 s; main's three runs have none.
- Replayed with `run ID --like-phone` from what the phone kept, this PR's code and main's light the same lines at the same times. The Police run 2: 20/22 within 0.5 s on both, median 0.47 s apart from the phone, worst 2.4 s. Expresso Love run 1: 11/18 on both, median 0.23 s apart, worst 5.3 s. So the late stretches come from something the replay doesn't have, not from this PR's code. What it is isn't known.
- Measured, with 3 runs a song: this PR's Police is behind main's in runs 1 and 2, and ahead in run 3. Too few runs to tell the builds apart on the phone.
- Main's runs 2 and 3 were first run with the last run's lead still known (`lead 210` on the first hearing). The harness had given the app notification access after stopping it, which started it again, and it wrote what it had learnt back over the forgetting. The harness now gives access before it stops the app, and only when it's missing. Those runs were redone, starting with `lead null`.
- Run 3 of the Police first read 54 s behind: it kept one while, in an intro riff that fits two places almost equally. `behind` now leaves out a while that fits elsewhere at 0.8 of its best fit or more. False fits measured 0.91 to 0.95 of the best, true ones up to 0.85.
- On the phone the Police's first while starts at 11.4 s to 11.6 s, as the replay's does at 11 s. With the stand-in Shazam above it starts at 22.8 s, so the stand-in is further from the phone on this song.

### A faster player on the phone: VLC at 1.05x

VLC for Android 3.7.1 played the Police from a tagged local file, `phone XB7AmIsxzfI --app org.videolan.vlc --uri file:///sdcard/Music/crosstune-police.m4a`, at 1.05x set on the track in VLC, which keeps it when VLC is stopped. Its session says the rate (`speed=1.05` in `dumpsys media_session`), and the app's timing log has `speed=1.05` on every clock: 17 or 18 in each run.

| Build | Run | Words' clock | Within 0.5 s | Within 1 s | Median off |
|---|---:|---:|---:|---:|---:|
| This PR | 1 | 1.050x | 18/19 | 19/19 | 0.36 s |
| This PR | 2 | 1.050x | 18/19 | 19/19 | 0.30 s |
| Main | 1 | 1.050x | 18/19 | 19/19 | 0.32 s |

- Measured: main doesn't count the rate twice on the phone here, as it does in the 1.05x replay (1.1025x).
- Inferred, from main's log: only the first 3 hearings are Shazam's, with speeds 1.003, 1.046 and 1.040. Every later hearing is the player's own clock, which main gives speed 1.0, so the pace goes back to 1.0 by 49 s. In the replay every hearing is Shazam's, so main stays off.
- YouTube Music has no speed control: neither build shows one, and its session offers none (`actions=2600887`, no `ACTION_SET_PLAYBACK_SPEED`). The YouTube app has one, but it stops once Crosstune comes to the front.

`phone` reads the screen with uiautomator, and only one thing may read it at a time: with a T3 device session holding it, `uiautomator dump` exits 137, and `phone` now says so instead of asking whether the phone is unlocked.

## Words found with no timing, timed by ear (Issue #860)

`untimed` on the 42 prepped songs, their times taken off. "Heard" is the lines sung inside a while the app heard. "Off" is a line put more than 1.0 s from where it's sung. "Unheard" is a line put where nothing was heard.

| Try | Heard | Timed | Within 1.0 s | Within 0.5 s | Off | Unheard |
|---|---:|---:|---:|---:|---:|---:|
| Main: untimed words stay untimed | 989 | 0 | 0 | 0 | 0 | 0 |
| One global alignment of all heard words to all lines | 989 | 451 | 357 (36%) | | | 0 |
| Each line looked for in each while, then the heaviest chain in order | 989 | | 36% | | more | 0 |
| The same, with short lines kept only beside a neighbor, lines between two close ones filled, words heard 550 ms late | 989 | 456 | 375 (38%) | 317 (32%) | 99 | 0 |
| The same, played twice (`--again 12000`), as the app hears it till it's heard through twice | 1194 | 738 | 595 (50%) | 484 (41%) | 143 | 0 |
| The same, two plays, no two lines closer than 60 ms a letter (was 30) | 1194 | 724 | 594 (50%) | 485 (41%) | 130 | 0 |
| The same, one play | 989 | 447 | 372 (38%) | 316 (32%) | 92 | 0 |
| Two plays, and a line dropped when it's over 3 s from where the song's pace from the lines within 4 puts it | 1194 | 641 | 541 (45%) | 442 (37%) | 100 | 0 |

- Measured: 4 songs have 80% or more of their heard lines within 1.0 s after two plays (4wOLVrGHiIU 71/71, WEuDVcXnElc 49/49, xTvyyoF_LZY 34/36, ZnrSNEah_20 21/24). After one play, 2 do.
- Measured: of the lines put, 83% are within 1.0 s on one play, 82% on two.
- Issue #892, lines put far off. With 60 ms a letter, lines put over 3 s off go from 58 to 49 on two plays (48 to 43 on one), and over 10 s from 18 to 15 on one play, for 1 to 3 good lines. Kept.
- The pace rule above drops 52 good lines to take away 44 off ones, and lines over 10 s off barely fall (12 to 9): most far-off lines come in groups that agree with each other, like a chorus put at its other repeat. Not kept. Tried with windows of 3 to 6 lines and 3 s to 8 s: each takes away about one good line for each off one (27 good for 30 off at 4 lines and 5 s, 32 for 27 at 6 lines and 8 s).
- Measured, as a bound: 66% of the heard lines have 2 or more words alike heard near them. The rest whisper.cpp tiny doesn't hear well enough to tell.
- Measured on 6 songs: given the words sung near each while as its prompt (the truth, so an upper bound), whisper.cpp tiny hears enough to put 64% within 1.0 s, against 49% without. Prompting with the lines the app expects next got 53%, with more lines put off. Not adopted: it needs the native hearing changed.
- Japanese, Korean and other songs whisper.cpp tiny hears poorly time few lines: 0 to 28% each.

### Lines put far off (Issue #892)

`untimed` and `untimed --again 12000`, as above. "> 3 s" and "> 10 s" are lines put that far from where they're sung. Each row adds to the one before.

| Try | Plays | Timed | Within 1.0 s | Within 0.5 s | Off | > 3 s | > 10 s | Unheard | Worst |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Before (main at `e97d7985`) | 2 | 724 | 594 | 485 | 130 | 49 | 11 | 0 | +43.2 s |
| A line starts where its earliest word heard says (was: the median of the most that agree) | 2 | 723 | 627 | 509 | 96 | 41 | 12 | 0 | +51.3 s |
| A line left untimed when the best chain without it, found near there, weighs at least half its own weight less | 2 | 652 | 591 | 482 | 61 | 15 | 0 | 0 | -6.8 s |
| A line filled between two only with a voice heard where it'd be sung | 2 | 650 | 589 | 480 | 61 | 15 | 0 | 0 | -6.8 s |
| Each letter of a word heard a letter after the one before (was: all at the word's start) | 2 | 647 | 589 | 485 | 58 | 15 | 0 | 0 | +6.7 s |
| Before | 1 | 447 | 372 | 316 | 92 | 43 | 15 | 0 | +67.6 s |
| All four | 1 | 412 | 364 | 308 | 57 | 24 | 9 | 0 | +69.1 s |

- Before the changes, 70 of the 78 lines put 1 to 3 s off (two plays) were late, and the lines within 1.0 s were centered on 0. A line held, or with a rest in it, is sung slower than 70 ms a letter, so the words after its first say it starts too late.
- The earliest word alone beat every window of words that agree: 1.0 s, 1.5 s, 2 s and 3 s put 585 to 588 within 1.0 s, with 16 to 18 over 3 s off. 70 ms a letter is still best with it: 60 ms put 622, 80 ms 616.
- The rule that leaves a line untimed is a curve, not a cliff. Two plays, share of its weight and how near: 0.45 and 6 s put 597 within 1.0 s, 65 off, 19 over 3 s; 0.5 and 6 s 591, 61, 15; 0.55 and 6 s 580, 60, 14. 6 s against 5 s or 7 s moves each count by 1 to 4.
- Its first form compared one chain per line put, and took 1.3 s on this PC for the slowest song's last while. Worked out from the best chain ending at each line found, starting at each, and the best from each on that skips a line, it gives the same lines on every while of every song (one play and two), and takes 0.27 s (0.22 s before any of this).
- Not kept, each measured on two plays. On main: a stricter form that asks another line to be found near the one left out (569 within 1.0 s, 114 off, 37 over 3 s); dropping words heard in a clump at one time, where the model loses its timing (no better at any size). With the rows above: a line kept when two whiles found it at one place (600, 65, 17: the same curve); a line whose earliest word is past half or two thirds of it left untimed (547 or 572 within 1.0 s); one-letter pieces (Japanese, Korean) counting as half a word (Japanese goes from 17 good and 26 off to 3 and 5: turned off, not fixed); the speech model's lateness at 400, 700 or 850 ms (550 stays best).
- Lines filled between two found, for the review of PR #889: before the voice rule, 47 on one play (27 within 1.0 s, 3 off, 0 over 3 s, 17 with no truth) and 77 on two (49, 4, 0, 24). The voice rule takes 1 off and 3 untruthed lines on one play, and 2 good and 2 untruthed on two.
- Left: on one play, 9 lines are over 10 s off. They're whole choruses put at another time they're sung, each line in order with its neighbors, so taking one out leaves no chain as good.
- `untimed` now has a "Wrong" column: lines shown more than 1.0 s from where they're sung at any time in the replay, not only at the end. Main 173, now 97, on two plays.
- A word heard in unspaced writing (Japanese, Chinese, Korean) is cut into one piece a letter. Each piece was heard at the word's start. Now each is a letter (70 ms) after the one before, in the app (`SungTiming`, and `SungWords` for timed words) and in the replay's hearings. The truth is unchanged: the old way and the new one give the same start for 112 of the 113 cached lines in unspaced writing, and the last moves by 10 ms.

### Held out (Issue #892 review)

The parts are fixed by song ID (SHA-256 of the ID, mod 3), so a song stays in its part as songs are added. `--part tune` is parts 2 and 3 (29 songs), `--part held` is part 1 (13 songs). The constants are picked on `tune` and scored once on `held`. Two plays (`untimed --again 12000`).

The pick rule, set before the sweep: within 1.0 s at least as many as now (398), over 3 s no more than now (6), then the fewest off.

| Share of weight | Near 4 s | 5 s | 6 s (now) | 7 s | 8 s |
|---|---|---|---|---|---|
| 0.4 | 405 / 33 / 9 | 407 / 34 / 9 | 407 / 36 / 10 | 408 / 37 / 11 | 410 / 38 / 12 |
| 0.45 | 402 / 32 / 8 | 405 / 33 / 8 | 405 / 35 / 9 | 406 / 36 / 10 | 406 / 37 / 11 |
| 0.5 (now) | 395 / 30 / 6 | 398 / 31 / 6 | 398 / 32 / 6 | 399 / 34 / 8 | 399 / 37 / 11 |
| 0.55 | 389 / 30 / 6 | 392 / 31 / 6 | 392 / 32 / 6 | 393 / 34 / 8 | 393 / 37 / 11 |
| 0.6 | 387 / 30 / 6 | 390 / 31 / 6 | 390 / 32 / 6 | 390 / 34 / 8 | 390 / 37 / 11 |

Within 1.0 s / off / over 3 s, on `tune`. Then with share 0.5 and 5 s, a letter's time and the speech model's lateness:

| Letter | Late 400 ms | 550 ms (now) | 700 ms |
|---|---|---|---|
| 60 ms | 393 / 37 / 9 | 394 / 36 / 7 | 395 / 35 / 6 |
| 70 ms (now) | 393 / 36 / 7 | 398 / 31 / 6 | 394 / 35 / 5 |
| 80 ms | 393 / 37 / 7 | 396 / 38 / 7 | 397 / 37 / 6 |

The rule picks share 0.5, 5 s, 70 ms, 550 ms: one line off fewer than 6 s on `tune`. Scored:

| Timing | Part | Heard | Timed | Within 1.0 s | Within 0.5 s | Off | > 3 s | Unheard | Wrong |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Main | tune | 839 | 479 | 393 | 321 | 86 | 26 | 0 | 115 |
| Now (6 s) | tune | 839 | 430 | 398 | 330 | 32 | 6 | 0 | 58 |
| Main | held | 355 | 245 | 201 | 164 | 44 | 23 | 0 | 58 |
| Now (6 s) | held | 355 | 217 | 191 | 155 | 26 | 9 | 0 | 39 |
| Picked (5 s) | held | 355 | 216 | 190 | 154 | 26 | 9 | 0 | 38 |
| Main | all | 1194 | 724 | 594 | 485 | 130 | 49 | 0 | 173 |
| Now (6 s) | all | 1194 | 647 | 589 | 485 | 58 | 15 | 0 | 97 |
| Picked (5 s) | all | 1194 | 645 | 588 | 484 | 57 | 15 | 0 | 95 |

- 5 s moves every count by 1 or 2, on `held` and on all songs, and puts one fewer within 1.0 s. That's noise, so 6 s stays.
- On the held songs, lines off fall from 44 to 26 and over 3 s from 23 to 9, but 10 fewer are within 1.0 s (201 to 191). On `tune` 5 more are. So the rule that leaves a line untimed costs good lines on songs it wasn't picked on. Issue #928 has it.
- Per part, main → now, within 1.0 s / off / over 3 s: part 1 201 / 44 / 23 → 191 / 26 / 9, part 2 240 / 26 / 6 → 236 / 13 / 2, part 3 153 / 60 / 20 → 162 / 18 / 4.

### A letter of unspaced writing (Issue #892 review)

The review found that a heard word in unspaced writing gives each of its letters the word's own time in `SungWords.kt`, and asked for each letter its own time, as `SungTiming.kt` had at 70 ms. `all` (timed lyrics, from the start), within 0.3 s / 0.5 s / dark / early:

| `SungWords.kt` | All songs |
|---|---|
| Each letter at its word's start (main) | 312 / 624 / 85 / 407 |
| 70 ms a letter | 314 / 616 / 85 / 415 |
| 280 ms a letter | 312 / 618 / 85 / 413 |

Only Japanese songs move: T_lC2O1oIew, VK4PJfTMawc and ZS6p32gX_yk lose 1, 2 and 3 lines within 0.5 s at 280 ms. So `SungWords.kt` stays as on main.

The truths could have been the reason: they were made with each letter of a heard word at its word's start, as main's `SungWords.kt` does. They were made again for the 6 Japanese and Korean songs, in a folder of their own, from one GPU hearing each: with each letter at its word's start, every line is where the stored truth has it (113 of 113), so the hearing is the same; with each letter 280 ms after the one before, no line moves either (113 of 113). Whisper medium's Japanese words are short, and a line starts where one does. So the truths are right, and `SungWords.kt` does better with each letter at its word's start. Why is Issue #970.

Whisper tiny, which the app runs, hears Japanese as long phrases. A letter's share of its phrase is 463 ms (median; 300 to 610 ms between the quartiles). Of 38 lines whose first 3 letters were found in a heard phrase, these many start within 0.5 s of the truth: 10 with each letter at the phrase's start, 10 at 70 ms a letter, 13 at 300 ms, 17 at 400 ms, 14 at 500 ms, 15 at 600 ms.

So in `SungTiming.kt` a letter of unspaced writing (Han, kana, Hangul) counts as a few of ours, each 70 ms (`SpokenWords.sungLetters`), where heard words' letters are sung, where a line starts by the letters before a word, how far apart two lines may be at most, and where a line is likely in the song. Not for how close two lines may be, or how long a line is lit: see below. The replay's hearing (`heard_pieces`) does the same. Swept on `untimed --again 12000`, with the rule above (within 1.0 s / off / over 3 s / wrong):

| Letters | `tune` | `held` | All songs |
|---|---|---|---|
| 1 (70 ms, before) | 398 / 32 / 6 / 58 | 191 / 26 / 9 / 39 | 589 / 58 / 15 / 97 |
| 3 | 402 / 33 / 8 / 59 | 195 / 27 / 9 / 36 | |
| 4 (280 ms) | 403 / 33 / 6 / 58 | 196 / 28 / 8 / 37 | 599 / 61 / 14 / 95 |
| 5 | 404 / 34 / 7 / 57 | 199 / 25 / 6 / 34 | 603 / 59 / 13 / 91 |
| 6 | 400 / 36 / 7 / 60 | 197 / 27 / 6 / 34 | 597 / 63 / 13 / 94 |
| 8 | 393 / 37 / 8 / 63 | 197 / 23 / 5 / 31 | 590 / 60 / 13 / 94 |

- On `tune`, only 1 and 4 keep within the rule's bounds (at least 398 within 1.0 s, at most 6 over 3 s). Its last step picks 1, by one line off (32 against 33).
- 4 puts 5 more lines within 1.0 s on `tune` and 5 more on `held`, the songs it wasn't picked on. One line off more is noise, as with `SAME_PLACE_MS` above. So 4 it is.
- One play, 4 against 1: 417 / 369 / 311 / 56 / 20 / 0 / 58 against 412 / 364 / 308 / 57 / 24 / 0 / 59 (timed / within 1.0 s / within 0.5 s / off / over 3 s / unheard / wrong).
- Japanese songs, two plays, within 1.0 s / off: 16 / 14 → 24 / 19. The Korean song: 3 / 2 → 5 / 0.

### Fast singing in unspaced writing (Issue #892 re-review)

The second review found that the fewest ms between two lines (`FAST_LETTER_MS`, 60 ms a letter) counted a Japanese or Korean letter as 4, so 240 ms a letter. Sung faster, at 120 to 180 ms a letter, two lines heard one after the other can't both be in a chain, and neither is put. Main put both. The fastest anyone sings a syllable is about as fast as a Latin letter goes by, so that bound counts each letter written as 1 again. A line's time lit when the next isn't heard counts them as 1 too: 8 Japanese letters were lit 4.8 s, against main's 2.0 s.

The replay set has no such lines. Its 6 Japanese and Korean songs have 62 pairs of lines, one right after the other, both with a time in the truth. The fastest is sung at 248 ms a letter, start to start, just past the 240 ms bound; the median is 557 ms. So the replay couldn't show it, and the unit tests now hold it.

`untimed --again 12000` (within 1.0 s / off / over 3 s / wrong, then timed / within 1.0 s / within 0.5 s / off / over 3 s / unheard / wrong for one play):

| | `tune` | `held` | All songs | One play |
|---|---|---|---|---|
| Before | 403 / 33 / 6 / 58 | 196 / 28 / 8 / 37 | 599 / 61 / 14 / 95 | 417 / 369 / 311 / 56 / 20 / 0 / 58 |
| Now | 404 / 32 / 6 / 58 | 196 / 30 / 9 / 39 | 600 / 62 / 15 / 97 | 415 / 369 / 311 / 54 / 20 / 0 / 56 |

Only Japanese songs move. K0sXE5_JhMg gets 1 line more within 1.0 s and 2 fewer off. SaeY5sxStGM and ZS6p32gX_yk get 1 and 2 more off: a line sung 6.5 s after the one before is put 1.5 s after it, which the bound let through as 167 ms a letter. That's the price of letting fast singing through. `all` is as on main, every song.

### Words heard one at a time (Issue #892 third review)

The third review found that Korean heard a word at a time, as whisper hears it, was lit over 1 s early when sung fast. Each heard word gave the line's start by the letters before it at 280 ms a syllable, and the earliest won, so a line sung at 120 ms a syllable started (syllables before its last word) × 160 ms early. The replay set has too few fast lines in Japanese or Korean to show it (Issue #977).

`syncreplay.py synthetic` sings made-up lines in each writing at each pace: 50 songs of 8 lines, 3 or 4 words a line, heard a word at a time, 15% of words missed, ±80 ms on each, rests of up to 1.5 s. `--timing FILE` scores another `SungTiming.kt` on the same songs. `SyntheticSingingTest` holds the app to these numbers. Within 1.0 s / off, of 400 lines:

| Writing | Pace | Main | `6ff930c4` | Now |
|---|---:|---:|---:|---:|
| Latin | 120 ms | 394 / 0 | 383 / 0 | 388 / 0 |
| Latin | 400 ms | 314 / 67 | 382 / 3 | 382 / 3 |
| Japanese | 120 ms | 400 / 0 | 141 / 252 | 396 / 2 |
| Japanese | 150 ms | 400 / 0 | 256 / 144 | 394 / 5 |
| Japanese | 280 ms | 306 / 94 | 399 / 1 | 399 / 1 |
| Japanese | 400 ms | 202 / 194 | 397 / 2 | 397 / 2 |
| Korean | 120 ms | 400 / 0 | 152 / 241 | 395 / 3 |
| Korean | 150 ms | 400 / 0 | 261 / 139 | 398 / 0 |
| Korean | 280 ms | 321 / 77 | 400 / 0 | 400 / 0 |
| Korean | 400 ms | 199 / 198 | 396 / 2 | 396 / 2 |
| Chinese | 120 ms | 400 / 0 | 140 / 254 | 397 / 1 |
| Chinese | 400 ms | 191 / 205 | 399 / 1 | 399 / 1 |

At the paces not shown, now is within 5 lines of the best of the three in each writing; `synthetic` prints them all.

What changed: each word heard says where the line starts by its own start and the letters before it in the line. The letters go by as fast as the words heard from the first to the last say, between 20 ms and 70 ms a letter, and the earliest of those says where the line starts. Sung slower than 70 ms a letter, a word held or a rest makes the later words look late, and the first word heard says it.

Tried, two plays (within 1.0 s / off / over 3 s, and right on main but wrong now):

| Start rule | Replay | Synthetic |
|---|---|---|
| A Hangul syllable as 2 letters, not 4 (the reviewer's) | 599 / 63 / 15 | Korean 120 ms 375 to 377 within, 400 ms 342 |
| The pace fit from the first word to the last, from the first | 554 within | |
| The same pace, the earliest of each word's start (kept) | 597 / 63 / 15 / 7 | 382 to 400 within everywhere |
| One pace for the whole song, from the words heard | 558 to 603 within, 13 to 65 wrong | |

Item 2 of the review: a Japanese line not heard, whose last 2 letters start the next line, was lit where the next is sung, 1.4 to 7.9 s late. A line found by fewer than 3 words now also needs to be no closer to the line beside it than that line's syllables take at a quick pace (60 ms a letter, a syllable as 4). That's the bound main had for any line. Two plays: 597 / 61 / 14, and the wrong lines go from 7 to 6.

#### Against main

`untimed-main.json` keeps the lines main puts within 1.0 s, by how the replay was run (`--write-main`, with `--timing` main's `SungTiming.kt`): 594 on two plays, 372 on one. Every `untimed` run now prints, against it, "right on main, wrong now" (put over 1.0 s off), "right on main, dark now" (`-v` lists them) and "not right on main, right now".

| Timing | Plays | Timed | Within 1.0 s | Within 0.5 s | Off | > 3 s | Unheard | Wrong | Right on main, wrong now | Dark now | Right now, not on main |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Main | 2 | 724 | 594 | 485 | 130 | 49 | 0 | 173 | 0 | 0 | 0 |
| `6ff930c4` | 2 | 662 | 600 | 492 | 62 | 15 | 0 | 97 | 7 | 39 | 52 |
| Now | 2 | 658 | 597 | 499 | 61 | 14 | 0 | 93 | 6 | 45 | 54 |
| Main | 1 | 447 | 372 | 316 | 92 | 43 | 0 | 100 | 0 | 0 | 0 |
| `6ff930c4` | 1 | 415 | 369 | 311 | 54 | 20 | 0 | 56 | 7 | 22 | 26 |
| Now | 1 | 408 | 364 | 313 | 52 | 20 | 0 | 53 | 6 | 29 | 27 |

Tried to bring "wrong now" to 0, two plays then one (within 1.0 s / off / over 3 s, then wrong now). The first four were measured before item 2's fix, the rest after it:

| Change to the start rule | Two plays | One play |
|---|---|---|
| Main's rule: the median of the most starts within 1.5 s | 575 / 91 / 20, 1 | 1 |
| The earliest of the most starts within 0.5 to 1.5 s | 578 to 587 / 70 to 92, 3 to 5 | 2 to 4 |
| No start when the earliest is 0.5 to 1.5 s before the most that agree | 554 to 588 / 64 to 72 / 17 to 22, 6 to 11 | 2 to 4 |
| Words further apart than 200 ms a letter and 0 to 4 s held, split; the most words kept | 518 to 598 / 62 to 81 / 17 to 29, 5 to 13 | 1 to 5 |
| The first word dropped when further from the next than that (0 to 1 s held) | 590 to 597 / 61 to 74 / 16 to 19, 2 to 4 | 3 to 5 |
| No start when the first or any 2 words are that far apart | 580 to 600 / 60 to 63 / 15 to 21, 4 to 6 | 4 to 6 |
| The first word dropped, and no start when the earliest is 1 to 2.5 s before the most that agree | 580 to 594 / 61 to 63 / 16, 3 | 3 to 4 |

Each takes lines main had right back, and puts others off, some over 3 s: dropping a word heard early lights iH8To-d2pX0 line 3 7.2 s late, where its first word was right and the rest were heard 6.6 s later. Nothing in the times of one line's words tells a word heard early from a line held, so none is kept. The lines, sung / main / now, in s:

| Song, line | Plays | Sung | Main | Now | Why |
|---|---|---:|---:|---:|---|
| FXovf5dsRTw 16 | 2 | 64.0 | 63.1 | 63.0 | Put between two by its letters, 0.1 s from main |
| LhLmgg_u29k 6 | 1, 2 | 28.3 | 27.7 | 27.3 | Put between two by its letters; the line after is 1.0 s early on main too |
| YkLLcIKhJ64 24 | 1, 2 | 147.9 | 147.8 | 146.9 | Its first word heard 1.0 s before it's sung, the rest on time |
| iH8To-d2pX0 16 | 1, 2 | 369.2 | 369.1 | 371.7 | One match takes words of two singings of the line, 12 s apart |
| uEV4RqqnlSE 4 | 2 | 37.9 | 37.8 | 35.6 | A short word of it heard 2.3 s before the rest |
| uEV4RqqnlSE 3 | 2 | 35.5 | 35.5 | 34.1 | Put between line 2 and line 4 |
| BciS5krYL80 7 | 1 | 75.4 | 75.3 | 72.5 | Its one-letter first word heard 2.8 s before the next |
| CH6t8l-jGYI 16 | 1 | 173.0 | 173.1 | 171.8 | Its first word heard 1.6 s before the next |
| OtXIsUjDOk8 18 | 1 | 106.0 | 106.2 | 110.3 | Its words heard again 4.9 s later, nearer than 6 s, so taken as one place |

### The first word of a while (Issue #892, after main's `f8d8709b`)

In the four lines above whose first word is heard early (YkLLcIKhJ64 24, uEV4RqqnlSE 4, BciS5krYL80 7, CH6t8l-jGYI 16), that word is the first heard in its while: 0.44, 0.2, 0.0 and 1.34 s after it starts. The speech model pins it there. Of 721 first words of a while, 140 are in its first 100 ms and 317 in its first 0.5 s. Against the true start of a line, by the line's first word (`untimed --again 12000` inputs):

| A line's first word, heard | Words | Median | Over 1 s early | Over 1 s late | Within 1 s |
|---|---:|---:|---:|---:|---:|
| First in its while | 80 | -0.34 s | 18 | 6 | 56 |
| Later in its while | 739 | -0.17 s | 129 | 160 | 450 |

So a line's first word heard first in its while no longer says where the line starts, unless it is all that's heard of the line. A sound told of before it, as "[Music]", takes the while's start, so a word after one still counts. The made-up singing (`synthetic`) hears every first word where it's sung, so there the rule costs lines that were right.

Each try, two plays then one (within 1.0 s / off / over 3 s / wrong, then right on main, wrong now), and the made-up singing against the floors:

| Change | Two plays | One play | Made-up singing |
|---|---|---|---|
| Before: the merge with main | 597 / 61 / 14 / 93, 6 | 364 / 52 / 20 / 53, 6 | The floors |
| No start from the first word of a while | 598 / 59 / 16 / 87, 2 | 373 / 53 / 22 / 54, 2 | Not run: iH8To-d2pX0 3 goes 7.2 s late, its first word 8 s into the while, after "[MÚSICA]", and right |
| No start from the first word row of a while (a sound counts as a row) | 598 / 58 / 15 / 86, 2 | 372 / 52 / 21 / 53, 2 | Latin at 400 ms: 382 / 3 to 362 / 22 |
| That, only for a line's own first word | 598 / 58 / 14 / 86, 3 | 371 / 51 / 20 / 52, 3 | 5 rows down 1 to 3 lines |
| No start from a word heard in the first 550 ms of a while | 598 / 59 / 14 / 90, 4 | Not run | 8 rows down |
| The line's own first word rule, and all starts 200 ms later (to check the lean isn't a knob) | 593 / 64 / 15 / 91, 5 | Not run | Not run |
| Two lines in a chain share no heard word: one's last word before the other's first, with the plain earliest rule | 590 / 57 / 15, 1 new (xTvyyoF_LZY 2) | Not run | Not run |
| No start from the first word row, and no shared words | 595 / 55 / 15 / 82, 1 | 370 / 51 / 21 / 52, 1 | Not run |
| A line put between two at its own word heard alike | 569 / 85 / 25, 27 | Not run | Not run |
| A line put between two at the first voice in its span | Same totals: fixes FXovf5dsRTw 16, breaks WEuDVcXnElc 46 | Not run | Not run |
| **Now: the line's own first word rule, and no shared words** | **595 / 55 / 14 / 82, 2** | **369 / 50 / 20 / 51, 2** | **9 rows down 1 to 4 lines, 2 up** |
| That, and a line put between two at the first voice in its span | 595 / 55, 1 (WEuDVcXnElc 46) | 1 (OtXIsUjDOk8 18) | 12 rows down |
| That, and two singings with no word in common taken as two places | 568 / 40 / 6 / 70, 4 | Not run | Most rows down far |

"No shared words" fixes iH8To-d2pX0 16: its one match took "no hay nada más" from its own second half and from line 17, which starts with it. It costs lines whose end is the next one's start, as "your love" then "Your love" (xTvyyoF_LZY 40, 41, dark now).

| Timing | Plays | Timed | Within 1.0 s | Within 0.5 s | Off | > 3 s | Unheard | Wrong | Right on main, wrong now | Dark now | Right now, not on main |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Main | 2 | 724 | 594 | 485 | 130 | 49 | 0 | 173 | 0 | 0 | 0 |
| The merge with main | 2 | 658 | 597 | 499 | 61 | 14 | 0 | 93 | 6 | 45 | 54 |
| Now | 2 | 650 | 595 | 503 | 55 | 14 | 0 | 82 | 2 | 49 | 52 |
| Main | 1 | 447 | 372 | 316 | 92 | 43 | 0 | 100 | 0 | 0 | 0 |
| The merge with main | 1 | 408 | 364 | 313 | 52 | 20 | 0 | 53 | 6 | 29 | 27 |
| Now | 1 | 411 | 369 | 321 | 50 | 20 | 0 | 51 | 2 | 29 | 28 |

By part, within 1.0 s / off / over 3 s / wrong, main then now:

| Part | Plays | Main | Now | Right on main, wrong now |
|---|---|---|---|---|
| Tune | 2 | 393 / 86 / 26 / 115 | 400 / 30 / 5 / 49 | FXovf5dsRTw 16 |
| Tune | 1 | 245 / 62 / 28 / 66 | 246 / 31 / 11 / 32 | OtXIsUjDOk8 18 |
| Held | 2 | 201 / 44 / 23 / 58 | 195 / 25 / 9 / 33 | LhLmgg_u29k 6 |
| Held | 1 | 127 / 30 / 15 / 34 | 123 / 19 / 9 / 19 | LhLmgg_u29k 6 |

The lines still right on main and wrong now, sung / main / now, in s:

| Song, line | Plays | Sung | Main | Now | Why |
|---|---|---:|---:|---:|---|
| FXovf5dsRTw 16 | 2 | 64.0 | 63.1 | 63.0 | Put between two by its letters: 1.008 s early, 0.1 s from main |
| LhLmgg_u29k 6 | 1, 2 | 28.3 | 27.7 | 27.3 | Put between two by its letters: 1.001 s early; the line after is 1.0 s early on main too |
| OtXIsUjDOk8 18 | 1 | 106.0 | 106.2 | 110.3 | Lines 15 and 18 are both "Tell it to my heart."; heard again 4.9 s later, nearer than 6 s, so taken as one place |

iH8To-d2pX0 13 is put 11.1 s early now, and 11.6 s early on main. Lines 13, 14 and 15 are all "Y por eso nena", heard at 352, 357.5 and 363 s; the true start puts line 13 at 363.6 s, which is likely line 15's.


### PR #917: shorter alignments and word ownership (2026-11-15)

The starting head was `d62eaa29`. Its unchanged timing reproduced two plays:
595 within 1 s, 55 off, 14 over 3 s, 0 unheard, and 2 main-right lines now wrong.
The original synthetic floors failed on the nine rows reported above. No floors,
fixtures, truths, tolerances, or measured lateness were changed in this pass.

The fast loop runs the actual Kotlin in bounded Podman containers. Each compiler
and JVM has 2 GiB RAM, no swap, 2 CPUs, no network, and nice level 10. Only explicit
source, cache, and output folders are mounted. The command-construction tests
failed before this limit was added. All 115 Python tests then passed, with 2 skips.

`timing-tests.py` also runs `SungTimingTest` and `SyntheticSingingTest` directly with
the app's Kotlin compiler and cached JUnit. It avoids an Android build. The first
new regressions failed on the starting code: a valid slow first word was discarded,
a line's only exact long word was ignored, and a partial match of a Latin letter
plus one word placed a longer line. The new repeated-word case and complete short
phrase case protect those surfaces too.

Commands, with recorded inputs retained in the shared ignored cache:

```sh
SYNCREPLAY_DIR=/mnt/data/agent-cache/crosstune-syncreplay nice -n 10 python3 scripts/syncreplay/timing-tests.py
SYNCREPLAY_DIR=/mnt/data/agent-cache/crosstune-syncreplay nice -n 10 python3 scripts/syncreplay/syncreplay.py untimed
SYNCREPLAY_DIR=/mnt/data/agent-cache/crosstune-syncreplay nice -n 10 python3 scripts/syncreplay/syncreplay.py untimed --again 12000
```

The causes were checked separately:

1. Dropping every first row discards valid words well inside the window. Only its
   existing edge margin is treated as the opening. A later word can confirm an
   opening word at the line's pace. This restored the slow synthetic starts.
2. Discarding every overlapping local alignment removes the shorter match needed
   to give the next line its own repeated words. Keeping prefixes and suffixes
   restored xTvyyoF_LZY lines 40 and 41 at 109.59 and 112.09 s, respectively.
   Matching fragments need two informative words. A complete short phrase such
   as "I know" is still allowed. Unspaced syllables keep their existing weight.
3. Interpolation placed LhLmgg_u29k line 6 before the preceding line's last matched
   word. Bounding it by that word changed 27.299 to 27.470 s. A one-word line can
   instead use its own matched word: FXovf5dsRTw line 16 changed 62.992 to 63.550 s.
   WEuDVcXnElc line 46 stayed within 1 s; blindly taking the next voice had broken it.
4. A single exact longest word can support a line between distant anchors. A short
   word such as "to", or one unspaced character, cannot supply that evidence alone.
   This restored the last missing Latin synthetic line at 400 ms per syllable.
5. A phrase heard as one ASR word has no independent timing inside it. Two such
   observations separated by more than the phrase's estimated duration are kept
   as competing places, even inside the general 6 s margin. Applying that shorter
   margin to all multi-word observations failed the original slow-Latin floor and
   the existing 6 s boundary tests, so that broader variant was rejected.
6. Heard positions include the letter position within a raw ASR word. An overlapping
   long unspaced word no longer hides overlap behind equal word-start timestamps.

All 44 focused JUnit tests pass, including every original synthetic floor. The
measured candidate before the behavior-preserving refactor gave two plays:
601 within 1 s, 57 off, 14 over 3 s, 0 unheard, 0 main-right lines now wrong.
The production refactor gave one play: 378 within 1 s, 53 off, 24 over 3 s,
0 unheard, 0 main-right lines now wrong. Full checks and final replay verification
are recorded below when complete. Private logs and inputs remain under `build/`
and the shared cache, outside git.


Production replay verification at `e9d9ef91` (timing logic unchanged by the additional tests):

| Plays | Main: within / off / over 3 s / wrong | Starting head | Final | Main-right now wrong | Unheard |
|---|---|---|---|---:|---:|
| Two | 594 / 130 / 49 / 173 | 595 / 55 / 14 / 82 | 601 / 57 / 14 / 91 | 0 | 0 |
| One | 372 / 92 / 43 / 100 | 369 / 50 / 20 / 51 | 378 / 53 / 24 / 56 | 0 | 0 |

Timed replay, `syncreplay.py all --gradle scripts/gradle-box/gradle-box.sh`, with
`GRADLE_BOX_MEMORY=7`: 312 / 624 / 85 / 407. The baseline is unchanged.
The final 44-test suite fails on the starting timing code: 7 new regression
assertions and the original synthetic-floor assertion. It passes on this code.
The two extra positive tests protect complete short phrases and separate parts
of one unspaced ASR word. They do not change the original assertions.

The original synthetic floor file remains byte-for-byte as restored in `d62eaa29`
and as recorded in `3ff563b1`. No new audio or ASR inference was needed.


### Alignment cost and equivalent candidate pruning

The first accurate version was too slow on repeated phrases. A final alignment
of xTvyyoF_LZY, in a 2-CPU container, took a median 9181.886187 ms over 3 measured
calls after a warm-up. Starting timing took 404.079944 ms. nOj6d-HOw2w and
BSTsnWoslP4 did not have that regression. These are wall times on a shared busy
machine, not isolated CPU throughput.

Each optimization kept the scores and line choices:

1. Direct word-position comparison removes a vararg allocation from every edge.
2. The minimum pace condition is rearranged once per candidate, rather than
   looking up the two line positions on every edge.
3. The chosen chain and its prefix weights share one pass. Suffix weights and
   the skipped-line alternatives share a second pass, with the original tie rule.
4. At the same line start, a stronger match consuming a narrower heard span can
   replace a weaker one in every chain. The word-count and single-word confidence
   classes are preserved too. Only such dominated candidates are removed.

The repeated song's candidates fell to 11696. Its measured median fell to
1449.303960 ms under load (maximum 1649.796224 ms), from 9181.886187 ms.
A later run, after local CI finished, measured 921.524433 ms (maximum
1289.367190 ms). The same later run measured 152.432058 ms for nOj6d-HOw2w and
40.017056 ms for BSTsnWoslP4. Load changed, so the latter is not a controlled
speedup ratio.

`timing-bench.py` and `TimingBenchmark.kt` retain the actual Kotlin benchmark.
They read the TSV inputs made by `untimed` without changing a replay request:

```sh
SYNCREPLAY_DIR=/mnt/data/agent-cache/crosstune-syncreplay nice -n 10 python3 scripts/syncreplay/timing-bench.py xTvyyoF_LZY nOj6d-HOw2w BSTsnWoslP4
```

Before and after the optimizer, all 84 recorded replay outputs were byte-for-byte
identical: one and two plays of each of the 42 recordings, including every round,
not only the final placements. Private copies are in this checkout's ignored
`build/syncreplay/optimization/`. A generated property test also repeats the same
hearing on 50 fake songs and requires every placement to stay unchanged.

Local `:app:ci` passed on `57a3a4e8` in 20 min 40 s, including 100% Kover line
coverage (22011 covered, 0 missed), detekt, lint, and both APKs. Its attached
command client ended with signal 143 while the container continued. Reconnecting
to that same container recovered exit 0 and the successful build log. The final
optimizer needs the same checks on its new head.

The full build also exposed an obsolete six-argument PIT test constructor. The
installed 1.30.2 API marks it deprecated and provides a seven-argument constructor
with `CodeSource`. The test now uses that constructor. Other Gradle deprecation
notices originate inside the existing Kover and Android plugins; no plugin or
app version is changed here.


### Root verification of the Mac review and chain index

The Mac review used a Python port. The parent then reproduced all three findings
with the actual Kotlin code, on both the reviewed `57a3a4e8` and the updated
`c8fd753c`. The new fake-data tests failed before the fill changes and pass now.

1. A one-word line could take a later line's word beyond its interpolated slot.
   In the fake case, `Hey` moved from main's 12586 ms to 14500 ms, after the next
   line at 13043 ms. Its search now stops at the next slot. It stays at 12586 ms.
2. Across a gap over 8 s, one word shared by two intervening lines put the wrong
   line 3.35 s late. Exact evidence now needs a word unique among those line
   indices. Repeated identical lines count separately. A unique word can put its
   line before the unreliable spacing guess. Conflicting placements stay dark.
3. A word inside a raw heard phrase had its character offset added twice. The
   fake line went to 23260 ms instead of 22000 ms. The offset is now used once.
4. An anchor's final word could identify a missing line too. A new test reproduced
   the extra line at 10700 ms. Far fills now use only heard pieces strictly
   between the anchors' owned pieces. The extra line stays dark.

The candidate graph also became large enough to make pairwise chain passes slow.
A fake 60-line song, 20 unspaced characters per line, heard twice, produced 33572
raw candidates. On `57a3a4e8`, its first actual Kotlin call took 48060.8 ms. The
existing dominance optimization on `c8fd753c` kept 16238 candidates and took
3521.3 ms cold and 2248.3 ms warm. The new two-dimensional prefix index keeps the
same ordering and tie rules without comparing every candidate pair.

Before the fill fixes, all 84 recorded replay outputs were byte-for-byte
unchanged by the new chain index, including every intermediate round. The
indexed fake case took 1843.35 ms cold and 551.08 ms on the second call. The
committed benchmark now has `--fake`; it needs no private recording. With the
final fill fixes, its median of three warm calls was 482.99 ms, maximum 562.07 ms,
in a 2-CPU, 2-GiB container. This measures JVM work on mr, not phone latency.

```sh
SYNCREPLAY_DIR=/mnt/data/agent-cache/crosstune-syncreplay nice -n 10 python3 scripts/syncreplay/timing-bench.py --fake
```

The parent compiled the final production Kotlin code and replayed all 84 inputs
again, then used `untimed_score` and `against_main` on the original truth and
main's stored right-line sets. No audio, truth, tolerance or case was changed.

| Plays | Main: within / off / over 3 s / wrong | Before this review | Now | Main right, now wrong | Unheard placements |
|---|---|---|---|---:|---:|
| Two | 594 / 130 / 49 / 173 | 601 / 57 / 14 / 91 | 601 / 57 / 14 / 89 | 0 | 0 |
| One | 372 / 92 / 43 / 100 | 378 / 53 / 24 / 56 | 379 / 51 / 22 / 54 | 0 | 0 |

The 51 focused tests pass, including the original synthetic accuracy floors.
The full Android checks, timed replay and fresh independent review are pending
on this final change. Their results must be checked before marking it ready.

### Two-play ownership and minimum pace after the second Mac review

The parent reproduced both new fill findings in Kotlin before changing the code:

1. A second hearing shifted by 50 ms put an extra line at 10750 ms, using the
   previous anchor's last word. An early partial copy of the next anchor put the
   same kind of extra line at 24950 ms. Both tests now leave the missing line dark.
   Word uniqueness includes both anchor lines, not just the intervening lines.
2. Two long-gap fills went to 14000 and 14100 ms despite a long line between
   their starts. Filled lines now use the same minimum pace bound as chain lines.
   Both conflicting fills stay dark.
3. The index now has a seeded property test: 200 fake point sets, both traversal
   directions, line groups, equal weights, equal bounds and duplicates. Each query
   and its weight must equal a pairwise search with the original tie rule. Making
   the inclusive y query exclusive fails this test on case 0.
4. The old mutation report exposed two test gaps. A fake pruning case loses two
   correct following lines when the span comparison is negated. A later exact
   word must recover its line's start by its preceding letters. Changing that
   subtraction to addition gives 17420 ms instead of 17000 ms. Both new tests
   fail those source mutants and pass on the production code.

All 57 focused Kotlin tests pass, including the original synthetic floors. The
parent reran all 84 recordings through the new Kotlin and scored the original
truth and main's stored right-line sets:

| Plays | Within 1 s | Off | Over 3 s | Wrong in any round | Main right, now wrong | Unheard placements |
|---|---:|---:|---:|---:|---:|---:|
| Two | 600 | 57 | 14 | 89 | 0 | 0 |
| One | 378 | 51 | 22 | 54 | 0 | 0 |

The targets still pass. The previous local full build was stopped because these
new production fixes replace its head. Full checks and a fresh review are needed
on this change. No GitHub run was cancelled by hand.

### Mixed-script pace and mutation follow-up

The next static review found that the new pace filter could remove a near fill
when its two anchors use different scripts. The sung-letter spacing and the
written-letter pace assign different weights to Japanese and Korean characters.
The parent reproduced the missing line in Kotlin. A near guess now stays inside
the anchors' written-letter pace window before the existing evidence and order
checks run. Both script directions pass, at 12280 and 11320 ms.

The old dominance-pruning layer is removed. The indexed chain no longer needs
it to meet the measured cost target. On the same 60-line fake input, three warm
calls with pruning had median 457.776 ms and maximum 461.020 ms. Without pruning,
the median was 415.042 ms and maximum 463.929 ms. Both measurements used the
bounded 2-CPU, 2-GiB container. This comparison does not establish a phone time
or a stable speed improvement. It supports removing the extra matching rules.

All 72 focused Kotlin tests pass. Sixteen targeted faulty versions fail those
tests: opening-word offsets, equal timestamp and pace bounds, one-word slots,
confidence through a skipped line, nonchronological heard-word ownership, fill
bounds and anchor ownership. The production code passes each same fixture.

The mutation report on aa518e15 caught 401 of 457 changes. Of its 56 remaining
leads, 13 belong to the removed pruning layer and 16 now have failing assertions.
Nineteen preserve the result under the timing rules: ties, redundant bounds,
unused successor cells and arithmetic that keeps the same candidate order.
Three affect unused generated getters. Five time out because they stop the
Fenwick index from advancing or terminating. These diagnoses do not change the
mutation workflow or hide its diagnostics. Fresh CI must check the new head.

The parent compiled the final code and replayed all 84 inputs again. The result
is unchanged: two plays 600 / 57 / 14 / 89; one play 378 / 51 / 22 / 54. Both
modes have zero main-right lines placed wrong and zero unheard placements. The
original synthetic floors, truth and tolerances are unchanged. The timed-lyrics
code is unchanged from the fresh 312 / 624 / 85 / 407 measurement.

Full local checks, current GitHub checks, a fresh review and app verification
remain pending on this follow-up.

### Pace across several mixed-script near fills

A fresh review found that clamping each guess to the anchors was insufficient.
Two adjacent fills could still have decreasing pace orders and both go dark.
The parent reproduced the exact fake input in Kotlin: only the 10000 and 16000 ms
anchors remained. The fixed result also contains 12352 and 14632 ms.

Near guesses now have nondecreasing pace orders across every intervening line,
inside both anchor bounds. Voice evidence and one-word matches keep their raw
spacing slots. A guessed start uses the next pace-adjusted bound. This last rule
also fixes a second measured case: when a pace bound moves the next guess, the
old raw slot end could remove the previous line at a valid start. Japanese and
Korean fixtures both need starts at 11740 and 13480 ms, between anchors at 10000
and 15220 ms. They failed before the bound change.

The 75 focused tests pass, including the original synthetic floors. A six-gap
sweep keeps both mixed-script fills within one second of their voice and at the
60 ms written-letter minimum pace. The initial sweep assertion incorrectly used
the model's 70 ms letter estimate; the check now uses the actual 60 ms minimum
pace requirement. Production did not change for that test correction.

The local build on d6b1377f was stopped because these production fixes replace
it. Current full CI, replay and a fresh review remain pending.

### Actual starts, owned anchor words and replay isolation

The next review measured two more near-fill cases. The parent reproduced both
in the bounded Kotlin runner before changing production. A one-word fill heard
at 12450 ms made both middle lines go dark. Its actual start now carries the
minimum written-letter pace into the next guess, which starts at 12690 ms.
The same test covers five word positions from 12350 through 12600 ms.

The slow-anchor probe had no independent voice for its first missing line: only
words already matched to the anchor. Placing that line would violate the zero
unheard-placement requirement. Matches now retain their exact heard positions.
A near fill cannot use an anchor's owned pieces as voice evidence. The original
probe keeps its heard second line at 15429 or 15451 ms and leaves the unheard
first line dark. A variant with independent voice for both middle lines keeps
starts at 13720/15460 and 14080/15820 ms. A further test leaves an infeasible
fill dark rather than moving the next sure anchor. Existing ownership and
minimum-pace tests still pass.

All 79 focused Kotlin tests pass, including the original synthetic floors. The
parent compiled this code and rescored all 84 recorded inputs again: two plays
600 / 57 / 14 / 89; one play 378 / 51 / 22 / 54. Both modes still have zero
main-right lines placed wrong and zero unheard placements. The fake 60-line
benchmark measured 552.305 ms median and 658.490 ms maximum over three warm
calls, in the same bounded JVM container. No phone latency is claimed.

The review also reproduced duplicate mounts when SYNCREPLAY_DIR is unset. The
container builder now combines identical destinations with their required
access and refuses conflicting sources. Both the real JUnit runner (79 tests)
and the fake benchmark run with that variable unset. Timing overrides are
immutable snapshots under their content hashes. Compiler results are built in
separate temporary folders and published atomically. New cache identities avoid
reusing entries made by the old mutable-input path. Deterministic tests cover a
second caller between hashing and reading, simultaneous builders, failed builds
and old unverified entries. The replay Python suite passes 124 tests, two skipped.

A focused license test run exposed a separate build-runner collision: two
containers used different Gradle homes but the same checkout's file-hash cache.
Queue tickets now name each checkout, including --also, before taking a build
slot. Overlapping builds wait; distinct checkout builds still run together.
Repeated --also paths run and mount once. All 54 build-runner tests pass, with
real fake Git checkouts, whitespace in paths and the existing Mac-tool probes.
Ruff and ShellCheck pass.

The earlier full local build on 170240ab passed in 14m 5s: 3792 tests, zero
failures or errors, four skips, and 22104 covered lines with zero missed lines,
methods or classes. It predates these production fixes. Fresh full checks, the
focused license test, independent review and final app verification are pending.

### Final test barriers and runner publication

The full local Gradle checks on 5b521a18 passed in 15m 34s: 3796 tests, zero
failures or errors, four skips, and 22107 covered lines with zero missed lines,
methods or classes. Branch coverage was 23012 covered and 2564 missed. The
wrapper then exited 127 because its file had been edited in place during that
run; Bash read a later byte offset from the changed file. A deterministic test
reproduced the same command-not-found failure. The runner now parses its full
body as a function before starting work. All 58 runner tests pass, including
editing the active file, and a fresh full command must confirm a zero exit.

The final static review found no blocking app-code finding. Its small ticket
publication window was also reproduced: a later build could overtake a ticket
whose name existed only in its parent's memory. Naming and publication now
happen under one short lock. Checkout paths are stored beside the memory ticket
so older scripts still read the exact requested memory. Tests cover a held
publication and the intermediate multi-line tickets while older builds finish.

GitHub on 87f3cc14 exposed a test boundary race: the title is translated after
the lyric lines. The reopen test took its request snapshot before that initial
title request finished, then counted it as work caused by reopening. It now
waits for the title meaning too and asserts that the meaning is kept. All three
focused reopen tests pass. No application source changed for these final test
and runner fixes; the 5b521a18 APK and recorded replay still name the app code.

### Shell helper edits and clock-safe checkout order

The full local Gradle run on 6281a8ec finished in 13m 12s with 3796 tests, zero
failures or errors, four skips and 22107 covered lines with zero missed lines,
methods or classes. Its shell was interrupted while the container kept running.
The parent collected the container's zero exit, then repeated the full command
with its completed outputs cached: BUILD SUCCESSFUL in 29s, wrapper exit zero.

The final runner review reproduced two more failures. Editing each-checkout.sh
during an --also build changed Bash's later byte offsets, just as in the main
runner. The parent reproduced that failure, plus the same failure in both golden
writers and the recording helper. Each now parses its complete body before work
starts. An exit on the same parsed line preserves the result when the body returns.
The golden tests check the output and worktree cleanup; the recording test checks
conversion and restored settings. Its failure case also found a recorder left
running when the driving command failed. Cleanup now stops and reaps that child.

A backwards clock, or an equal timestamp with a different PID, could put the next
ticket before an active one and run two builds in one checkout. Both cases failed
in the parent's held-build test. While holding the publication lock, the runner
now chooses a timestamp greater than every queued ticket. All 62 runner tests
pass. ShellCheck and Ruff pass. No app production or test sources changed in this
round. Fresh CI and independent review of these shell fixes remain pending.

### Remaining local setup helpers and deterministic queue regression

The next shell review reproduced the active-edit failure in syncreplay/setup.sh
and record/emulator.sh. The parent held each real script behind a fake tool,
edited that script in place and reproduced exit 127. Both now parse their full
body before work starts. The setup test covers both CPU and ROCm paths and
checks every install call. The emulator setup test checks the final language,
mode, notification commands and completion message; every device command is fake.

The review also found that the equal-clock subcase could inherit stale started
markers from a failed earlier subcase. Cleanup now runs in finally. Each case
holds both jobs and reads their published tickets. It requires the second stamp
to exceed the first, independent of PID digit counts or scheduling. Against the
old runner, the backward case fails with 1e18 not greater than 2e18; the equal
case fails with 2e18 not greater than 2e18. Both cases pass with the fix. The
golden-writer cases also clean their held jobs and markers before asserting;
both still fail independently on the original writers.

A blank line keeps the internal parsing comment out of recording help. Tests
check both recording helpers' actual usage output. All 64 runner tests and 12
recording setup tests pass. The stand-in device changes at once, so its existing
setup tests no longer spend five seconds in each fake locale change. ShellCheck
and Ruff pass. No app source or app test changed. Fresh CI and a new review of
this final shell-only delta remain pending.

### Final small helper findings

The setup test now keeps its original diagnostic if setup exits before its first
uv call: missing install logs no longer raise FileNotFoundError in finally, and
the held-tool wait reports an early exit with its output. The icon helper also
used one shared /tmp image. The parent reproduced both concurrent runs choosing
that same file, and a failed conversion leaving it behind. It now uses mktemp
inside the extension's icon folder and an EXIT trap. The complete short POSIX
body is parsed before work starts too. No release or publication step changed.

All 66 runner tests pass. New cases hold two writers at their final conversion,
require separate inputs and check cleanup; a failing conversion preserves exit
7 and removes its input. Real ImageMagick 7.1.1-43 in a bounded, offline container
generated all four icons with zero pixel differences from the old script. Two
real concurrent runs also passed and left no temporary image. ShellCheck and
Ruff pass. The app and its tests remain unchanged from the successful full checks.
Fresh GitHub checks and an independent review of these small fixes are pending.

### POSIX signal cleanup and unshipped icon scratch files

The icon-helper review found that dash does not run an EXIT trap for an unhandled
signal. The parent reproduced all six cases with the actual script: INT, TERM
and HUP, sent either to the shell or its process group. Each left its temporary
image. Explicit traps now exit with 130, 143 or 129 and run the shared cleanup.
The test resets inherited signal dispositions and starts a private process group,
so an ignored background INT cannot make a false pass. All six cases now remove
the image and keep the expected status; all 67 runner tests pass.

Temporary images now live in the extension's ignored .cache folder, outside the
icons folder that the build copies. This also keeps an untrappable kill's leftover
file out of packaged assets. The parent installed the locked extension packages
in an isolated container (zero audit vulnerabilities), left a stray icon image in
.cache, then ran the real extension build offline. Chrome and Firefox each built
28 files, with no cache or stray image copied. git check-ignore also recognizes
the scratch path. The app and its tests are unchanged. ShellCheck and Ruff pass.
Fresh GitHub checks and review of this final signal fix remain pending.
