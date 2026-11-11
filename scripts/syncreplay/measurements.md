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
