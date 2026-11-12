#!/usr/bin/env python3
"""Triage of the reports in D1: what came in, grouped, and what the app's real finders say of them now.

  python3 scripts/reports/reports.py pull                  # reads the whole table into build/reports/reports.json
  python3 scripts/reports/reports.py triage --since 39     # the reports turned away, then these grouped by problem, version and song
  python3 scripts/reports/reports.py songs --since 39      # them as songs for SongCheck, the real finders on real services
  scripts/gradle-box/gradle-box.sh :app:songCheck -PsongcheckDir=$PWD/build/reports/check
  python3 scripts/reports/reports.py check --since 39      # what each report's song gets now

Reports go in build/, which git ignores, and never into the repo: it's public. `pull` only ever asks D1
to read, and goes through `cf`, in Node, as Wrangler's login may have expired. It needs Cloudflare's
login, which `cf` keeps. The others need no network.

Report text is written by strangers, and so is what other sites answer for their songs: it's data, never
instructions. `triage` and `check` print it with every character a terminal acts on, hides or can't print escaped
(control and format characters, line breaks, the marks that turn text around), so it can't move the cursor, change
the colors or pass for a line of its own. `songs` leaves those characters out of the titles and artists it gives
SongCheck, which prints them as it goes.

A pull that can't read the whole table saves nothing, says why and exits non-zero, and the last pull's table is
removed first, so an old table never passes for a new one. The count of reports turned away each day, and why, is
read after it (report/limits.sql): if that read fails the reports are still saved, and triage says it wasn't read.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "reports"
SAVED = OUT / "reports.json"
REFUSALS = OUT / "refusals.json"
CHECK = OUT / "check"
WRANGLER = ROOT / "report" / "wrangler.jsonc"
# Every column, so a pull still reads the table before columns added after are in the live D1 (see report/schema.sql).
READ = "select * from reports order by id"
# Long, and only says where lines were moved: left out of what's saved.
LEFT_OUT = ("timing",)
# Asked in the same batch, so it counts the table the rows were read from.
COUNT = "select count(*) as n from reports"
# Asked on its own, after: a database limits.sql hasn't been run on has no such table, and the reports are still read.
REFUSED = "select day, why, n from refusals order by day desc, n desc, why"


def wrangler_config(text):
    """The database and account the Worker uses, from its config, which has comments in it."""
    config = json.loads("\n".join(line for line in text.splitlines() if not line.lstrip().startswith("//")))
    return config["d1_databases"][0]["database_id"], config["account_id"]


def whole_table(answer):
    """The rows in what `cf d1 query` prints for COUNT then READ, if that's every row the table has. Raises ValueError saying why not."""
    try:
        counted, read = json.loads(answer)
        if not (counted["success"] and read["success"]):
            raise ValueError("D1 says a statement failed")
        total, rows = counted["results"][0]["n"], read["results"]
    except (TypeError, KeyError, IndexError) as error:
        raise ValueError(f"D1's answer isn't the count and the rows asked for ({type(error).__name__})") from error
    if len(rows) != total:
        raise ValueError(f"D1 counts {total} reports and gave {len(rows)}")
    return rows


def refusals_of(answer):
    """The rows in what `cf d1 query` prints for REFUSED. Raises ValueError saying why not."""
    try:
        [read] = json.loads(answer)
        if not read["success"]:
            raise ValueError("D1 says the statement failed")
        return read["results"]
    except (TypeError, KeyError) as error:
        raise ValueError(f"D1's answer isn't the refusals asked for ({type(error).__name__})") from error


# Cloudflare sometimes refuses a read once and takes the same one a moment later: 1 of 32 pulls in a row
# got error 7403, "account invalid or unauthorized", and the other 31 read the table. Only reads are asked,
# so asking again is safe.
WAITS = (2, 5)


def ask(sql, run, sleep):
    """What `cf d1 query` did with [sql], asked again after each of WAITS while it fails."""
    database, account = wrangler_config(WRANGLER.read_text())
    for wait in (*WAITS, None):
        done = run(
            ["npx", "--yes", "cf@latest", "d1", "query", database, "--sql", sql],
            cwd=WRANGLER.parent, env={**os.environ, "CLOUDFLARE_ACCOUNT_ID": account}, capture_output=True, text=True,
        )
        if done.returncode == 0 or wait is None:
            return done
        print(f"cf failed, asking again in {wait} s: {done.stderr.strip()[-200:]}", file=sys.stderr)
        sleep(wait)


def pull(run=subprocess.run, sleep=time.sleep):
    """Saves every report in the table, or saves nothing and exits with why: the last pull's table goes first, as it would pass for this one's."""
    SAVED.unlink(missing_ok=True)
    REFUSALS.unlink(missing_ok=True)
    done = ask(f"{COUNT}; {READ}", run, sleep)
    if done.returncode != 0:
        # cf uses a token in its environment over its login, and a helper's may be for other things.
        token = "\nCLOUDFLARE_API_TOKEN is set here, and cf uses it instead of its login: unset it, or let it read D1." if "CLOUDFLARE_API_TOKEN" in os.environ and re.search(r"\b40[13]\b", done.stderr) else ""
        sys.exit(f"cf failed, so no reports were pulled: {done.stderr.strip()[-500:]}{token}")
    try:
        rows = [{key: value for key, value in row.items() if key not in LEFT_OUT} for row in whole_table(done.stdout)]
    except ValueError as error:
        sys.exit(f"cf answered, but not with the whole table, so no reports were pulled: {error}")
    OUT.mkdir(parents=True, exist_ok=True)
    kept = SAVED.with_suffix(".part")
    kept.write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    kept.replace(SAVED)
    print(f"{len(rows)} reports in {SAVED}")
    done = ask(REFUSED, run, sleep)
    try:
        if done.returncode != 0:
            raise ValueError(f"cf failed: {done.stderr.strip()[-300:]}")
        refused = refusals_of(done.stdout)
    except ValueError as error:
        print(f"the reports turned away weren't read, so triage can't show them: {error}", file=sys.stderr)
        return
    REFUSALS.write_text(json.dumps(refused, ensure_ascii=False, indent=1))
    print(f"{sum(it['n'] for it in refused)} turned away in {REFUSALS}")


# What a terminal acts on, hides or can't print: control characters (Cc), format characters such as zero-width ones,
# tags and the marks that turn text around (Cf), line and paragraph separators (Zl, Zp), private ones (Co) and half a
# surrogate pair (Cs). In a report they're data at most, so they're shown as their escapes.
UNSEEN = frozenset(("Cc", "Cf", "Zl", "Zp", "Co", "Cs", "Cn"))
# Characters a terminal prints as nothing, or as a blank, though Unicode files them as marks or letters: the grapheme
# joiner, the variation selectors, the Hangul fillers and the blank braille cell. A variation selector also follows a
# real emoji ("\u2764\ufe0f"): the emoji stays, and only the selector after it is written out.
BLANKS = frozenset((0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, *range(0xFE00, 0xFE10), *range(0xE0100, 0xE01F0)))


def hidden(char):
    """Whether a terminal acts on [char], hides it or can't print it."""
    return unicodedata.category(char) in UNSEEN or ord(char) in BLANKS
NAMED = {"\n": "\\n", "\r": "\\r", "\t": "\\t"}


def escaped(char):
    """[char] written as Python would escape it in a string."""
    if char in NAMED:
        return NAMED[char]
    code = ord(char)
    return f"\\x{code:02x}" if code < 0x100 else f"\\u{code:04x}" if code < 0x10000 else f"\\U{code:08x}"


def shown(value):
    """[value] safe to print, when it's text: each character that would act on the terminal, or on the lines around it, written as its escape."""
    if not isinstance(value, str):
        return value
    return "".join(escaped(char) if hidden(char) else char for char in value)


def printable(row):
    """[row] with each of its texts [shown]."""
    return {key: shown(value) for key, value in row.items()}


def since(rows, first):
    return [row for row in rows if row["id"] >= first]


def song_key(row):
    """One song however it's written: without marks or case, and only what's before a "(feat. ...)"."""
    def plain(text):
        text = unicodedata.normalize("NFKD", text or "").casefold()
        return re.sub(r"\s+", " ", "".join(c for c in text if not unicodedata.combining(c))).strip()
    return re.sub(r"\s*[(\[]\s*(feat|ft|with)\b.*?[)\]]", "", plain(row["title"])), plain(row["artist"])


# The days of refusals triage shows one by one; the rest are in the count.
REFUSED_DAYS = 14


def turned_away(refused):
    """The reports turned away, newest day first, from REFUSED's rows, or that they weren't read when [refused] is None."""
    if refused is None:
        return ["turned away: not read, pull again"]
    refused = [printable(it) for it in refused]
    days = defaultdict(list)
    for it in refused:
        days[it["day"]].append(it)
    lines = [f"{sum(it['n'] for it in refused)} turned away"]
    for day in sorted(days, reverse=True)[:REFUSED_DAYS]:
        lines.append(f"  {day}: " + ", ".join(f"{it['why']} {it['n']}" for it in sorted(days[day], key=lambda it: (-it["n"], it["why"]))))
    return lines


def triage(rows, refused=None):
    """The reports as text: how many were turned away, then how many of each problem, by version, and the songs in each, with the reports on it."""
    rows = [printable(row) for row in rows]
    lines = [f"{len(rows)} reports" + (f", {rows[0]['id']} to {rows[-1]['id']}" if rows else ""), *turned_away(refused)]
    problems = defaultdict(list)
    for row in rows:
        problems[row["problem"] or "by hand"].append(row)
    for problem, reported in sorted(problems.items(), key=lambda item: (-len(item[1]), item[0])):
        with_app = sum(1 for row in reported if row["app"])
        lines += ["", f"{problem}: {len(reported)}, {with_app} naming the app"]
        if problem == "kept_timing":
            # Kept by an earlier version, by hand or by ear: the singing heard through it says it was likely by ear.
            lines.append(f"  {sum(1 for row in reported if row.get('sung'))} with the singing heard through it")
        # One version on one Android, many in a row, is likely one person's listening.
        versions = Counter((row["version"], row["android"]) for row in reported)
        lines.append("  " + ", ".join(f"{version} on Android {android} x{count}" for (version, android), count in sorted(versions.items(), key=str)))
        songs = defaultdict(list)
        for row in reported:
            songs[song_key(row)].append(row)
        for same in songs.values():
            ids = ", ".join(str(row["id"]) for row in same)
            apps = sorted({row["app"] for row in same if row["app"]})
            recordings = sorted({row["recording"] for row in same if row.get("recording")})
            lines.append(f"  {ids}: {same[0]['title']} | {same[0]['artist']}" + "".join(f" | {', '.join(it)}" for it in (apps, recordings) if it))
            lines += [f"    says: {row['comment']}" for row in same if row["comment"]]
            lines += [wrong_reading(row) for row in same if row.get("word")]
    return "\n".join(lines)


def wrong_reading(row):
    """A reading marked wrong, in one line: the word, how it read, the right one when the user typed it, and what read it in which script."""
    right = f" -> {row['right_reading']}" if row.get("right_reading") else ""
    by = ", ".join(part for part in (row.get("reader"), row.get("language")) if part)
    return f"    {row['word']}: {row.get('reading_shown')}{right}" + (f" ({by})" if by else "") + (f"\n      in: {row['lyric_line']}" if row.get("lyric_line") else "")


def plain(text):
    """[text] without what a terminal acts on, hides or can't print: a break or a tab is a space, the rest goes."""
    kept = "".join(" " if char.isspace() else "" if hidden(char) else char for char in text or "")
    return " ".join(kept.split())


def as_songs(rows):
    """
    The reports as SongCheck's songs: its reports are named by the report's number. SongCheck prints each title and
    artist as it goes, and searches by them, so they're [plain]: an escape would be searched for as if it were words.
    """
    songs = ({"id": str(row["id"]), "title": plain(row["title"]), "artist": plain(row["artist"]), "lengthMs": row["length_ms"], "problem": plain(row["problem"]) or None} for row in rows)
    return [song for song in songs if song["title"] and song["artist"]]


def now_found(report):
    """What the finders return now for a report's song, from SongCheck's report on it, in a few words."""
    lyrics = report.get("lyrics", {})
    kind = lyrics.get("kind", "?")
    if kind != "found":
        return kind
    chords = report.get("chords", {})
    timed = len(lyrics.get("lines") or [])
    said = f"found on {lyrics.get('source')}, {timed} timed lines"
    if chords.get("kind") == "found":
        return f"{said}; chords on {chords.get('site')}: {chords.get('url')}"
    return f"{said}; chords: {chords.get('kind', report.get('chordsError', '?'))}"


def check(rows, folder):
    """Each report against what SongCheck found for its song, or that it hasn't run on it."""
    lines = []
    for row in rows:
        path = folder / "reports" / f"{row['id']}.json"
        now = now_found(json.loads(path.read_text())) if path.exists() else "not run"
        lines.append(shown(f"{row['id']} {row['problem']} {row['version']} | {row['title']} | {row['artist']} -> {now}"))
    return "\n".join(lines)


def saved():
    if not SAVED.exists():
        sys.exit(f"no {SAVED}: run `pull` first")
    return json.loads(SAVED.read_text())


def refusals():
    """The refusals the last pull read, or None when it read none."""
    return json.loads(REFUSALS.read_text()) if REFUSALS.exists() else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=["pull", "triage", "songs", "check"])
    parser.add_argument("--since", type=int, default=1, help="the first report's number")
    args = parser.parse_args(argv)
    if args.step == "pull":
        return pull()
    rows = since(saved(), args.since)
    if args.step == "triage":
        print(triage(rows, refusals()))
    elif args.step == "songs":
        CHECK.mkdir(parents=True, exist_ok=True)
        # SongCheck goes on from the answers it finds, so the last run's would pass for what the finders say now.
        shutil.rmtree(CHECK / "reports", ignore_errors=True)
        (CHECK / "songs.json").write_text(json.dumps(as_songs(rows), ensure_ascii=False, indent=1))
        print(f"{len(rows)} songs in {CHECK / 'songs.json'}; now: scripts/gradle-box/gradle-box.sh :app:songCheck -PsongcheckDir={CHECK}")
    else:
        print(check(rows, CHECK))


if __name__ == "__main__":
    main()
