#!/usr/bin/env python3
"""The golden readings: every reading the readings audit found right, locked in readings-golden.tsv so no change breaks one.

  python3 scripts/readingsaudit/readingsaudit.py golden    # lock every reading right today, through gradle-box; a row already there stays
  python3 scripts/readingsaudit/readingsaudit.py golden --accept WORD KANA   # and drop the lock on WORD read as KANA, on purpose
  python3 scripts/readingsaudit/readingsaudit.py hole      # how many right readings the file guards on every wider stretch of their line

This file is part of the lock: check_golden.py asks a pull request that changes it to say so in its body.
"""
import json
from collections import Counter, defaultdict

import readingsaudit as audit

# What ReadingsGoldenTest reads back: every reading the audit found right, on the shortest stretch of its line that reads so
# with any more of the line around it too, and CONTEXT more letters of the line on each side.
GOLDEN_REL = "app/src/test/resources/com/astrovm/crosstune/readings-golden.tsv"
GOLDEN_FILE = audit.ROOT / GOLDEN_REL
# What the readings are made of. A pull request that changes one has to change the golden file too, or say so.
READING_SOURCES = (
    "app/src/main/java/com/astrovm/crosstune/Readings.kt",
    "app/src/main/java/com/astrovm/crosstune/Furigana.kt",
    "app/src/main/java/com/astrovm/crosstune/UniDic.kt",
    "app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt",
    "app/src/main/resources/com/astrovm/crosstune/old-kana.txt",
)
# The dictionaries are Kuromoji's jars, named with their version in the app's build file, or in Gradle's version catalog
# when the build moves them there.
READING_BUILDS = ("app/build.gradle.kts", "gradle/libs.versions.toml")
READING_BUILD_WORD = "kuromoji"
GOLDEN_COUNT = "# rows: "
# The letters of the line each row holds on each side of its stable stretch, where the line has them. A rule that reads a
# word by the text next to its stretch fails a row too: the fewest that catch every break measured in PR #931.
CONTEXT = 3
GOLDEN_HEADER = [
    "# Locked readings. Each row is a reading the app gives the way the song is sung: its kana, and where they go.",
    "# Every reading the audit found right is here, on the shortest stretch of its line that the app reads the same way",
    f"# alone and with any more of the line around it, and up to {CONTEXT} more letters of the line on each side of that.",
    "# A song never has more than a quarter of its lines here whole: past that, a reading is locked on the longest",
    "# stretch short of its line that reads so.",
    "# Rows are sorted by the stretch, so songs are not in order. There is no song title and no artist name.",
    "# Columns, separated by a tab: line, word, index, kana, ruby, dictionary.",
    "# line is the stretch. index is empty when the word is in it once. Otherwise it is the occurrence, from 0.",
    "# ruby is the word's parts as the app splits it, text=reading or text alone, joined by |.",
    "# dictionary is ipadic, unidic or both.",
    "# Regenerate: python3 scripts/readingsaudit/readingsaudit.py golden",
    "# The command adds rows. It does not remove or change a row.",
    "# A row the app now reads otherwise is a regression, and the command exits 1.",
    "# A change you mean drops that lock: python3 scripts/readingsaudit/readingsaudit.py golden --accept WORD KANA",
]
# What ends a line for Python's splitlines or Kotlin's lineSequence, and the field separator. GoldenReadings.LINE_ENDS is the same.
LINE_ENDS = set("\t\n\r\x0b\x0c\x1c\x1d\x1e\x85  ")
DICTIONARIES = ("ipadic", "unidic", "both")


def find_starts(line, word):
    """Where [word] starts in [line], including a word that overlaps itself. GoldenReadings.starts does the same."""
    starts, at = [], 0
    while True:
        found = line.find(word, at)
        if found < 0:
            return starts
        starts.append(found)
        at = found + 1


def iter_audit_lines(folder):
    """Each lyric line the audit compared, once per song: its text and the two dictionaries' parts."""
    found = folder / "audit"
    if not found.is_dir():
        raise SystemExit(f"no audit in {found}")
    for path in sorted(found.glob("*.json")):
        song = json.loads(path.read_text())
        seen = set()
        for line in song.get("lines") or []:
            text = line.get("text")
            # A chorus sung again is the same line. A line with no ruby has nothing to lock.
            if not text or text in seen or not line.get("truth") or "builtIn" not in line or "uniDic" not in line:
                continue
            seen.add(text)
            yield line


def ruby_over(parts, start, end):
    """The parts over line[start:end], text=reading or text alone, joined by |. GoldenReadings.rubyAt writes them the same way."""
    out, at = [], 0
    for text, reading in parts:
        stop = at + len(text)
        if stop > start and at < end:
            out.append(text if reading is None else f"{text}={reading}")
        at = stop
    return "|".join(out)


def right_cases(folder):
    """Every reading the audit found right, once: {(line, word, occurrence from 0, ipadic or unidic): (kana, ruby)}."""
    cases = {}
    for line in iter_audit_lines(folder):
        text = line["text"]
        units = audit.truth_units(text, line["truth"])
        for name, key in (("ipadic", "builtIn"), ("unidic", "uniDic")):
            for word, shown, _right, ok, (start, end) in audit.compare_line(text, line[key], units):
                if ok and word:
                    cases[(text, word, find_starts(text, word).index(start), name)] = (shown, ruby_over(line[key], start, end))
    return cases


def writable(word, ruby):
    """Whether a row can hold this word and ruby: a | or = in the word would make the ruby mean something else."""
    return not set(word) & (LINE_ENDS | {"|", "="}) and not set(ruby) & LINE_ENDS


def dictionary_tag(readers):
    """ipadic, unidic, or both when this one occurrence is right in each."""
    if {"ipadic", "unidic"} <= readers:
        return "both"
    return "ipadic" if "ipadic" in readers else "unidic"


def readers_of(tag):
    return {"ipadic", "unidic"} if tag == "both" else {tag}


def check_fields(row):
    """A row ReadingsGoldenTest reads back as written: six fields, none with a tab or a line end, and a line that is no comment."""
    if any(set(part) & LINE_ENDS for part in row) or not row[0] or row[0].startswith("#"):
        raise SystemExit(f"a golden row can't be written: {row!r}")
    line, word, field, kana, ruby, tag = row
    parts = [part.split("=", 1) for part in ruby.split("|")]
    if (tag not in DICTIONARIES or (field and not field.isdigit()) or "".join(p[0] for p in parts) != word
            or "".join(p[-1] for p in parts) != kana or word not in line):
        raise SystemExit(f"a golden row needs a dictionary, an index, and ruby that spells its word as its kana: {row!r}")


def row_sort_key(row):
    return (row[0], row[1], -1 if row[2] == "" else int(row[2]), row[3], row[4], row[5])


def read_golden(path):
    """The rows already locked. Missing file means none yet. A broken row stops the command."""
    if not path.exists():
        return []
    rows = []
    for raw in path.read_text(encoding="utf-8").split("\n"):
        raw = raw.rstrip("\r")
        if not raw or raw.startswith("#"):
            continue
        fields = tuple(raw.split("\t"))
        if len(fields) != 6:
            raise SystemExit(f"a golden row needs 6 fields: {raw}")
        check_fields(fields)
        rows.append(fields)
    return rows


def write_golden(path, rows):
    for row in rows:
        check_fields(row)
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = GOLDEN_HEADER + [f"{GOLDEN_COUNT}{len(rows)}"] + ["\t".join(row) for row in sorted(rows, key=row_sort_key)]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def units(line, at):
    """Where letter [at] of [line] is in UTF-16, as Kotlin counts: a letter past U+FFFF is two."""
    return at + sum(1 for c in line[:at] if ord(c) > 0xFFFF)


def letters(line, at):
    """Which letter of [line] starts at UTF-16 unit [at]: the other way round from [units]."""
    found = count = 0
    while count < at:
        count += 2 if ord(line[found]) > 0xFFFF else 1
        found += 1
    return found


def golden_contexts(folder, job):
    """GoldenContexts' answer to [job], run in the build box: which rows still hold, and each case's shortest stretch."""
    # The job holds the songs' lines, so it is in the audit folder, and goes once it is answered.
    asked = folder / "golden-job.json"
    asked.write_text(json.dumps(job, ensure_ascii=False))
    answer = folder / "golden-answer.json"
    answer.unlink(missing_ok=True)
    try:
        if audit.songcheck(folder, "com.astrovm.crosstune.songcheck.GoldenContexts") != 0 or not answer.exists():
            raise SystemExit("GoldenContexts did not answer; its output is above")
        return json.loads(answer.read_text())
    finally:
        asked.unlink(missing_ok=True)
        answer.unlink(missing_ok=True)


def span_of(row, line, word, start):
    """Where [row]'s stretch is in [line] as (from, to), when it is the part around the occurrence of [word] at [start]; else None."""
    starts = find_starts(row[0], word)
    occurrence = int(row[2]) if row[2] else 0
    if occurrence >= len(starts):
        return None
    begin = start - starts[occurrence]
    return (begin, begin + len(row[0])) if begin >= 0 and line[begin:begin + len(row[0])] == row[0] else None


def song_lines(folder):
    """Each song's lyric lines, once each: {song: set of lines}. A row whose stretch is one of them holds that line whole."""
    return {path.stem: {line["text"] for line in json.loads(path.read_text()).get("lines") or [] if line.get("text")}
            for path in sorted((folder / "audit").glob("*.json"))}


# The most of one song's lines the file may hold whole. Bits of lines are fine; a song is not.
WHOLE_SHARE = 0.25


def row_on(line, word, start, span, kana, ruby):
    """The row that locks the occurrence of [word] at [start] on line[span]: stretch, word, index, kana, ruby."""
    stretch = line[span[0]:span[1]]
    found = find_starts(stretch, word)
    return (stretch, word, str(found.index(start - span[0])) if len(found) > 1 else "", kana, ruby)


def cases_tried(cases, keys, rows):
    """For each case, the rows of [rows] that hold its word and ruby for its dictionary around it, with their span in its line."""
    by_word = defaultdict(list)
    for at, row in enumerate(rows):
        by_word[(row[1], row[4])].append(at)
    tried = {}
    for key in keys:
        line, word, occurrence, tag = key
        start = find_starts(line, word)[occurrence]
        found = ((at, span_of(rows[at], line, word, start)) for at in by_word[(word, cases[key][1])] if tag in readers_of(rows[at][5]))
        tried[key] = [(at, span) for at, span in found if span]
    return tried


def job_of(cases, keys, rows, tried, lines, width=0):
    """What GoldenContexts reads: the rows to check, each case with the spans to try in UTF-16, every lyric line, and the
    letters of context a row holds on each side of its stable stretch."""
    return {"rows": [[row[0], row[1], int(row[2]) if row[2] else None, row[4], row[5]] for row in rows],
            "cases": [[*key[:3], cases[key][1], key[3], [[units(key[0], a), units(key[0], b)] for _at, (a, b) in tried[key]]]
                      for key in keys],
            "lines": sorted(lines), "width": width}


def span_in(line, answered):
    """A span GoldenContexts answered, in UTF-16, as letters of [line]; None stays None."""
    return answered and (letters(line, answered[0]), letters(line, answered[1]))


def cap_whole_lines(songs, kept, placed):
    """Hold each song to WHOLE_SHARE of its lines whole. [kept] is the rows already in the file, which stay; [placed] maps
    each new case to [row, fallback]. A line over the limit moves its cases to their fallback. The cases moved."""
    moved = set()
    for song in sorted(songs):
        lines = songs[song]
        fixed = {row[0] for row in kept if row[0] in lines}
        new = Counter(row[0] for row, _fallback in placed.values() if row[0] in lines and row[0] not in fixed)
        # A case with no fallback has to stay on its line.
        stuck = {row[0] for row, fallback in placed.values() if row[0] in new and fallback is None}
        room = max(0, int(len(lines) * WHOLE_SHARE) - len(fixed) - len(stuck))
        keep = stuck | set(sorted(set(new) - stuck, key=lambda line: (-new[line], line))[:room])
        for key, (row, fallback) in placed.items():
            if row[0] in new and row[0] not in keep:
                placed[key] = [fallback, None]
                moved.add(key)
    return moved


def summary(rows, songs):
    """What the file holds, in numbers: whole lyric lines, the largest share of one song's lines, and the stretches' lengths."""
    stretches = {row[0] for row in rows}
    lengths = sorted(len(stretch) for stretch in stretches)
    every = set().union(*songs.values()) if songs else set()
    shares = [(len(stretches & lines), len(lines)) for lines in songs.values() if lines]
    most = max(shares, key=lambda share: share[0] / share[1], default=(0, 0))
    median = lengths[len(lengths) // 2] if lengths else 0
    return (f"{len(stretches & every)} whole lyric lines, at most {most[0]} of a song's {most[1]} lines, "
            f"stretches median {median} and longest {lengths[-1] if lengths else 0} characters")


def golden(folder, dest, accept, contexts=golden_contexts, width=CONTEXT):
    """Write [dest] from the audit, every right reading locked on a stable stretch with [width] letters of its line on each
    side. Add rows. Never drop or edit one, unless [accept] names its word and kana."""
    accept = {tuple(pair) for pair in accept}
    old = read_golden(dest)
    cases = right_cases(folder)
    songs = song_lines(folder)
    keys = sorted(key for key, (_kana, ruby) in cases.items() if writable(key[1], ruby))
    tried = cases_tried(cases, keys, old)
    answer = contexts(folder, job_of(cases, keys, old, tried, set().union(*songs.values()) if songs else set(), width))
    merged, regressions = defaultdict(set), []
    for row, verdict in zip(old, answer["rows"]):
        if not all(v for v in verdict if v is not None):
            if (row[1], row[3]) in accept:
                continue
            regressions.append(row)
        merged[row[:5]] |= readers_of(row[5])
    kept = set(merged)
    placed, not_locked = {}, [key for key, (_kana, ruby) in cases.items() if not writable(key[1], ruby)]
    for key, got in zip(keys, answer["cases"]):
        line, word, occurrence, _tag = key
        kana, ruby = cases[key]
        stretch, fallback = span_in(line, got["stretch"]), span_in(line, got["fallback"])
        if stretch is None:
            not_locked.append(key)
            continue
        # A row already there that holds all of the stretch locks it as well, so a second run writes the same file.
        if any(a <= stretch[0] and stretch[1] <= b and old[at][:5] in kept for at, (a, b) in tried[key]):
            continue
        start = find_starts(line, word)[occurrence]
        placed[key] = [row_on(line, word, start, stretch, kana, ruby), fallback and row_on(line, word, start, fallback, kana, ruby)]
    moved = cap_whole_lines(songs, kept, placed)
    for key, (row, _fallback) in placed.items():
        merged[row].add(key[3])
    rows = [key + (dictionary_tag(readers),) for key, readers in merged.items()]
    write_golden(dest, rows)
    for row in sorted(regressions, key=row_sort_key):
        print(f"regression: {row[1]}\t{row[3]}\t{row[5]}\t{row[0]}\t{row[4]}")
    for key in sorted(not_locked):
        print(f"not locked: {key[1]}\t{cases[key][0]}\t{key[3]}")
    for key in sorted(moved):
        print(f"not fully guarded: {key[1]}\t{cases[key][0]}\t{key[3]}")
    locked = len(cases) - len(not_locked)
    share = f"{100 * locked / len(cases):.1f}%" if cases else "none"
    print(f"golden: {len(rows)} rows, {len(set(rows) - set(old))} new, {len(regressions)} regressions, "
          f"{locked} of {len(cases)} right readings locked ({share}), {len(moved)} not fully guarded, {dest}")
    print(f"golden: {summary(rows, songs)}")
    return 1 if regressions or not_locked else 0


def hole(folder, files, contexts=golden_contexts):
    """How well each golden file in [files] guards the readings the audit found right, with the app as it is now.
    A case is guarded when a row of the file holds on its stretch and on every wider one of its line. A case the app
    now reads otherwise on its whole line is caught when a row it has fails."""
    cases = right_cases(folder)
    keys = sorted(key for key, (_kana, ruby) in cases.items() if writable(key[1], ruby))
    owner, rows = [], []
    for path in files:
        found = read_golden(path)
        owner += [path] * len(found)
        rows += found
    tried = cases_tried(cases, keys, rows)
    answer = contexts(folder, job_of(cases, keys, rows, tried, set()))
    for path in files:
        mine = [at for at, who in enumerate(owner) if who == path]
        failing = sum(1 for at in mine if not all(v for v in answer["rows"][at] if v is not None))
        unguarded = broken = missed = 0
        for key, got in zip(keys, answer["cases"]):
            spans = [verdict for (at, _span), verdict in zip(tried[key], got["spans"]) if owner[at] == path]
            unguarded += not any(stable for _good, stable in spans)
            if not got["whole"]:
                broken += 1
                missed += all(good for good, _stable in spans)
        print(f"hole: {path}: {len(mine)} rows, {failing} fail. Of {len(keys)} right readings, {unguarded} read otherwise "
              f"on some wider stretch than every row they have, or have none. {broken} read otherwise on their whole line, "
              f"and {missed} of those have no row that fails.")
    return 0
