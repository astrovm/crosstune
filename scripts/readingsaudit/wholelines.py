#!/usr/bin/env python3
"""The second lock on the readings: every line of the audit read whole, by main's code and by this checkout's.

  python3 scripts/readingsaudit/readingsaudit.py lines                          # origin/main against HEAD
  python3 scripts/readingsaudit/readingsaudit.py lines --base REF               # against another commit
  python3 scripts/readingsaudit/readingsaudit.py lines --accept WORD KANA       # WORD read as KANA changes, on purpose
  python3 scripts/readingsaudit/readingsaudit.py lines --accept-romaji          # the romaji of lines read right changes, on purpose
  python3 scripts/readingsaudit/readingsaudit.py lines --new-audit              # the audit folder is new, on purpose

The golden rows hold a reading with a few letters of its line, so a rule that looks further along the line, or at
its length, gets past them. This reads each line of the audit whole, as the app shows it, with both dictionaries,
once with main's code and once with this checkout's, and compares what main's code gets right:

  ruby          the kana over each kanji, and where they go (Readings.of)
  shown         the same once UtaTen's readings are put in, as the app does when it finds them (Readings.corrected)
  romaji        a line's romaji, as said and as typed, when its kana are the same on both sides
  words         the word sheet's words: reading and romaji of each word whose kanji all read right (Readings.words)
  shown words   the same over the shown line (Readings.asShown)
  script        a song main's code takes for Japanese, and so reads (Readings.scriptOf)

A change to any of them is named, and the command exits 1. With none, it prints the line the pull request's body
needs: `golden: lines checked DIGEST`. The digest names what the readings are made of on both sides, so
check_golden.py asks for the command again when either side changes. The line is the author's word that they ran
this: CI can't run it, as the lines are songs' words.

So the lines it reads are the ones the golden file was made from, the golden file's header names the audit: how
many lines, a hash of them, and how many readings the code reads right on them. An audit folder that is empty, cut
short or another one fails, and so does main's code reading another number right than main's file says, as that is
another build. The command writes the line for this checkout's code, to commit; --new-audit names a new audit.

The lines stay in the audit folder, never in Git. main's code is checked out in a worktree of its own in the agent
cache, and both checkouts are built in one gradle-box run, then the worktree is removed.

This file is part of the lock: check_golden.py asks a pull request that changes it to say so in its body.
"""
import contextlib
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import check_golden
import golden
import readingsaudit as audit

TEST = "com.astrovm.crosstune.songcheck.WholeLines"
HARNESS = "app/src/test/java/com/astrovm/crosstune/songcheck/WholeLines.kt"
JOB = "lines-job.json"
DICTIONARIES = ("ipadic", "unidic")
# What each layer compares, by the name the command prints.
PARTS = (("ruby", "parts"), ("shown", "shown"))
ROMAJI = (("romaji", "parts", "romaji"), ("shown romaji", "shown", "shownRomaji"))
WORDS = (("words", "parts", "words"), ("shown words", "shown", "shownWords"))
ACCEPT_ROMAJI = "golden: accept romaji"
# The golden file's line that names the audit it was made from. golden.py keeps it as it is.
AUDIT_LINE = re.compile(r"# audit: (\d+) lines, (\d+) right readings, sha256 ([0-9a-f]{16})")


def audit_lines(folder):
    """Each line the audit has UtaTen's ruby for, once for each ruby: [(text, truth)]."""
    seen, out = set(), []
    for line in golden.iter_audit_lines(folder):
        key = (line["text"], json.dumps(line["truth"], ensure_ascii=False))
        if key not in seen:
            seen.add(key)
            out.append((line["text"], line["truth"]))
    return out


def audit_songs(folder):
    """Each song's lyric lines, in order, for the script the app takes it for."""
    return [[line["text"] for line in json.loads(path.read_text()).get("lines") or [] if line.get("text")]
            for path in sorted((folder / "audit").glob("*.json"))]


def audit_hash(entries, songs):
    """16 hex digits that name the audit's lines and songs."""
    return hashlib.sha256(json.dumps([entries, songs], ensure_ascii=False).encode()).hexdigest()[:16]


def audit_line(header):
    """(lines, right readings, hash) from a golden file's text; None when it has no audit line."""
    found = next((AUDIT_LINE.fullmatch(line.rstrip("\r")) for line in header.split("\n") if line.startswith(golden.AUDIT)), None)
    return found and (int(found[1]), int(found[2]), found[3])


def answer_name(at):
    return f"lines-answer-{at}.json"


def read_whole(folder, trees, entries, songs):
    """[entries] and [songs] read by each checkout of [trees], the last one building them all in one gradle-box run.
    A list of answers, one for each tree: {"ipadic": [...], "unidic": [...], "scripts": [...]}."""
    # The job holds the songs' lines, so it is in the audit folder, and goes once it is answered.
    job = folder / JOB
    names = [folder / answer_name(at) for at in range(len(trees))]
    for name in names:
        name.unlink(missing_ok=True)
    job.write_text(json.dumps({
        "lines": [{"text": text, "truth": truth} for text, truth in entries], "songs": songs,
        "answers": {str(Path(tree).resolve()): name.name for tree, name in zip(trees, names)},
    }, ensure_ascii=False))
    try:
        code = audit.songcheck(folder, TEST, root=trees[-1], also=trees[:-1])
        if code != 0 or not all(name.exists() for name in names):
            raise SystemExit(f"WholeLines did not answer in {', '.join(map(str, trees))}; its output is above")
        return [json.loads(name.read_text()) for name in names]
    finally:
        job.unlink(missing_ok=True)
        for name in names:
            name.unlink(missing_ok=True)


@contextlib.contextmanager
def checkout(repo, commit, near):
    """[commit] in a worktree of its own in a new folder in [near], with this checkout's WholeLines, so both read the
    lines with the same harness. The worktree and its folder go after, whatever happens."""
    folder = Path(tempfile.mkdtemp(prefix="crosstune-lines-", dir=near))
    tree = folder / "base"
    try:
        subprocess.run(["git", "-C", str(repo), "worktree", "add", "--detach", "-q", str(tree), commit], check=True)
        try:
            (tree / HARNESS).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(Path(repo) / HARNESS, tree / HARNESS)
            yield tree
        finally:
            subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(tree)], check=True)
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def kana_of(parts):
    return "".join(text if reading is None else reading for text, reading in parts)


def right_units(text, truth, parts):
    """Each reading [parts] gets right: (word, kana, (start, end))."""
    return [(word, kana, span) for word, kana, _wanted, ok, span in audit.compare_line(text, parts, audit.truth_units(text, truth))
            if ok and word]


def word_spans(text, words):
    """Each word of the word sheet where it is in [text]: {(start, end): word}. A word not found is left out."""
    out, at = {}, 0
    for word in words:
        found = text.find(word[0], at)
        if found >= 0:
            out[(found, found + len(word[0]))] = word
            at = found + len(word[0])
    return out


def right_words(text, parts, rights, words):
    """The words of [words] whose kanji all read right in [parts], and whose reading is those parts' kana:
    {(start, end): word}."""
    good = {at for _word, _kana, (a, b) in rights for at in range(a, b)}
    starts, at = {0}, 0
    for part_text, _reading in parts:
        at += len(part_text)
        starts.add(at)
    out = {}
    for (a, b), word in word_spans(text, words).items():
        han = [at for at in range(a, b) if audit.is_han(text[at])]
        if not han or not set(han) <= good or a not in starts or b not in starts:
            continue
        over, at = [], 0
        for part_text, reading in parts:
            if a <= at and at + len(part_text) <= b:
                over.append((part_text, reading))
            at += len(part_text)
        if word[1] is not None and audit.hiragana(word[1]) == audit.hiragana(kana_of(over)):
            out[(a, b)] = word
    return out


def changes(entries, songs, before, after, accepted=(), accept_romaji=False):
    """What [before] reads right that [after] reads otherwise, layer by layer, as (layer, word, kana, dictionary, line,
    before, after), less the (word, kana) pairs in [accepted], and romaji when [accept_romaji]; the readings [before]
    gets right, {layer: count}; the accepted pairs that changed, and whether romaji did."""
    found, used, right = set(), set(), dict.fromkeys(("ruby", "shown", "words", "shown words"), 0)
    romaji_used = False
    for name in DICTIONARIES:
        for (text, truth), was, now in zip(entries, before[name], after[name]):
            for layer, key in PARTS:
                for word, kana, (start, end) in right_units(text, truth, was[key]):
                    right[layer] += 1
                    old, new = golden.ruby_over(was[key], start, end), golden.ruby_over(now[key], start, end)
                    if old == new:
                        continue
                    if (word, kana) in accepted:
                        used.add((word, kana))
                    else:
                        found.add((layer, word, kana, name, text, old, new))
            for layer, key, field in ROMAJI:
                same_kana = kana_of(was[key]) == kana_of(now[key])
                if right_units(text, truth, was[key]) and same_kana and was[field] != now[field]:
                    if accept_romaji:
                        romaji_used = True
                    else:
                        found.add((layer, "", "", name, text, " / ".join(was[field]), " / ".join(now[field])))
            for layer, key, field in WORDS:
                rights = right_units(text, truth, was[key])
                theirs = word_spans(text, now[field])
                for span, word in right_words(text, was[key], rights, was[field]).items():
                    right[layer] += 1
                    if theirs.get(span) == word:
                        continue
                    pair = (word[0], audit.hiragana(word[1]))
                    if pair in accepted:
                        used.add(pair)
                    else:
                        shown = theirs.get(span)
                        found.add((layer, *pair, name, text, " ".join(map(str, word[1:])), shown and " ".join(map(str, shown[1:]))))
    for lines, was, now in zip(songs, before["scripts"], after["scripts"]):
        if was == "JAPANESE" and now != was:
            found.add(("script", "", "", "", lines[0] if lines else "", was, str(now)))
    return sorted(found, key=lambda change: tuple(map(str, change))), right, sorted(used), romaji_used


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def golden_at(repo, commit):
    return check_golden.file_at(repo, commit, golden.GOLDEN_REL)


def audit_problem(recorded, lines, digest):
    """Why the audit folder is not the one the golden file was made from; None when it is."""
    if recorded is None:
        return "readings-golden.tsv names no audit. Record this one, on purpose: run again with --new-audit"
    if (recorded[0], recorded[2]) != (lines, digest):
        return (f"The audit folder has {lines} lines, sha256 {digest}; readings-golden.tsv was made from {recorded[0]} "
                f"lines, sha256 {recorded[2]}. Use the audit it was made from: this one is empty, cut short or another. "
                f"A new audit on purpose: run golden, then lines --new-audit")
    return None


def write_audit_line(path, line):
    """The golden file at [path] with its audit line set to [line], just before the row count."""
    text = path.read_text(encoding="utf-8").split("\n")
    text = [old for old in text if not old.startswith(golden.AUDIT)]
    at = next(i for i, old in enumerate(text) if old.startswith(golden.GOLDEN_COUNT))
    path.write_text("\n".join(text[:at] + [line] + text[at:]), encoding="utf-8")


def lines(folder, accepted, base="origin/main", repo=audit.ROOT, read=read_whole, new_audit=False, accept_romaji=False):
    """The command: 0 and the body's lines when nothing main's code reads right changes, else 1 and each change."""
    paths = [*golden.READING_SOURCES, *golden.READING_BUILDS, golden.GOLDEN_REL]
    if git(repo, "status", "--porcelain", "--", *paths):
        raise SystemExit("Commit the readings change and the golden file first: the digest names commits, and this reads the checkout.")
    base_commit, head = git(repo, "rev-parse", "--verify", f"{base}^{{commit}}"), git(repo, "rev-parse", "HEAD")
    if subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", base_commit, head], check=False).returncode != 0:
        raise SystemExit(f"HEAD does not have {base} ({base_commit[:8]}) in it: merge main first, then run lines again.")
    entries, songs = audit_lines(folder), audit_songs(folder)
    if not entries:
        raise SystemExit(f"The audit folder {folder} has no lines with UtaTen's ruby.")
    digest = audit_hash(entries, songs)
    recorded = audit_line(golden_at(repo, head))
    if not new_audit and (problem := audit_problem(recorded, len(entries), digest)):
        raise SystemExit(problem)
    with checkout(repo, base_commit, folder.parent) as tree:
        before, after = read(folder, [tree, repo], entries, songs)
    found, right, used, romaji_used = changes(entries, songs, before, after, {tuple(pair) for pair in accepted}, accept_romaji)
    # main's golden file names what main's code reads right on the same audit: another number is another build.
    on_main = audit_line(golden_at(repo, base_commit))
    if on_main and on_main[::2] == (len(entries), digest) and on_main[1] != right["ruby"]:
        raise SystemExit(f"main's code reads {right['ruby']} readings right on these lines, and main's readings-golden.tsv "
                         f"says {on_main[1]}: the build or the dictionaries are not the ones it was made with.")
    print(f"{len(entries)} whole lines and {len(songs)} songs, {base} ({base_commit[:8]}) against HEAD ({head[:8]}); "
          f"right on main: " + ", ".join(f"{count} {layer}" for layer, count in right.items()) + f"; {len(found)} change")
    for layer, word, kana, name, text, was, now in found:
        print(f"  {layer}: {word} {kana} {name}: {was} -> {now}  in {text}")
    if found:
        print("A change on purpose: run again with --accept WORD KANA, or --accept-romaji, and put each in the body as it prints")
        return 1
    # The file names the audit, and what this checkout reads right on it, for the pull requests after this one.
    head_right = changes(entries, songs, after, after)[1]["ruby"]
    want = f"{golden.AUDIT}{len(entries)} lines, {head_right} right readings, sha256 {digest}"
    if recorded != audit_line(want):
        write_audit_line(Path(repo) / golden.GOLDEN_REL, want)
        print(f"readings-golden.tsv now names this audit: {want}\nCommit it, then run lines again.")
        return 1
    print("Put these lines in the pull request's body:")
    for word, kana in used:
        print(f"{check_golden.ACCEPT}{word} {kana}")
    if romaji_used:
        print(ACCEPT_ROMAJI)
    print(f"{check_golden.LINES}{check_golden.readings_digest(repo, base_commit, head)}")
    return 0
