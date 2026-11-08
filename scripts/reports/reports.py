#!/usr/bin/env python3
"""Triage of the reports in D1: what came in, grouped, and what the app's real finders say of them now.

  python3 scripts/reports/reports.py pull                  # reads the whole table into build/reports/reports.json
  python3 scripts/reports/reports.py triage --since 39     # grouped by problem, version and song
  python3 scripts/reports/reports.py songs --since 39      # them as songs for SongCheck, the real finders on real services
  scripts/gradle-box/gradle-box.sh :app:songCheck -PsongcheckDir=$PWD/build/reports/check
  python3 scripts/reports/reports.py check --since 39      # what each report's song gets now

Reports go in build/, which git ignores, and never into the repo: it's public. `pull` only ever asks D1
to read, and goes through `cf`, in Node, as Wrangler's login may have expired. It needs Cloudflare's
login, which `cf` keeps. The others need no network.

A pull that can't read the whole table saves nothing, says why and exits non-zero, and the last pull's table is
removed first, so an old table never passes for a new one.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "reports"
SAVED = OUT / "reports.json"
CHECK = OUT / "check"
WRANGLER = ROOT / "report" / "wrangler.jsonc"
COLUMNS = "id, at, version, android, title, artist, app, timed, synced, sung, length_ms, source, problem, comment"
# Every column but `timing`, which is long and only says where lines were moved.
READ = f"select {COLUMNS} from reports order by id"
# Asked in the same batch, so it counts the table the rows were read from.
COUNT = "select count(*) as n from reports"


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


def pull(run=subprocess.run):
    """Saves every report in the table, or saves nothing and exits with why: the last pull's table goes first, as it would pass for this one's."""
    SAVED.unlink(missing_ok=True)
    database, account = wrangler_config(WRANGLER.read_text())
    done = run(
        ["npx", "--yes", "cf@latest", "d1", "query", database, "--sql", f"{COUNT}; {READ}"],
        cwd=WRANGLER.parent, env={**os.environ, "CLOUDFLARE_ACCOUNT_ID": account}, capture_output=True, text=True,
    )
    if done.returncode != 0:
        # cf uses a token in its environment over its login, and a helper's may be for other things.
        token = "\nCLOUDFLARE_API_TOKEN is set here, and cf uses it instead of its login: unset it, or let it read D1." if "CLOUDFLARE_API_TOKEN" in os.environ and re.search(r"\b40[13]\b", done.stderr) else ""
        sys.exit(f"cf failed, so no reports were pulled: {done.stderr.strip()[-500:]}{token}")
    try:
        rows = whole_table(done.stdout)
    except ValueError as error:
        sys.exit(f"cf answered, but not with the whole table, so no reports were pulled: {error}")
    OUT.mkdir(parents=True, exist_ok=True)
    kept = SAVED.with_suffix(".part")
    kept.write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    kept.replace(SAVED)
    print(f"{len(rows)} reports in {SAVED}")


def since(rows, first):
    return [row for row in rows if row["id"] >= first]


def song_key(row):
    """One song however it's written: without marks or case, and only what's before a "(feat. ...)"."""
    def plain(text):
        text = unicodedata.normalize("NFKD", text or "").casefold()
        return re.sub(r"\s+", " ", "".join(c for c in text if not unicodedata.combining(c))).strip()
    return re.sub(r"\s*[(\[]\s*(feat|ft|with)\b.*?[)\]]", "", plain(row["title"])), plain(row["artist"])


def triage(rows):
    """The reports as text: how many of each problem, by version, and the songs in each, with the reports on it."""
    lines = [f"{len(rows)} reports" + (f", {rows[0]['id']} to {rows[-1]['id']}" if rows else "")]
    problems = defaultdict(list)
    for row in rows:
        problems[row["problem"] or "by hand"].append(row)
    for problem, reported in sorted(problems.items(), key=lambda item: (-len(item[1]), item[0])):
        with_app = sum(1 for row in reported if row["app"])
        lines += ["", f"{problem}: {len(reported)}, {with_app} naming the app"]
        # One version on one Android, many in a row, is likely one person's listening.
        versions = Counter((row["version"], row["android"]) for row in reported)
        lines.append("  " + ", ".join(f"{version} on Android {android} x{count}" for (version, android), count in sorted(versions.items(), key=str)))
        songs = defaultdict(list)
        for row in reported:
            songs[song_key(row)].append(row)
        for same in songs.values():
            ids = ", ".join(str(row["id"]) for row in same)
            apps = sorted({row["app"] for row in same if row["app"]})
            lines.append(f"  {ids}: {same[0]['title']} | {same[0]['artist']}" + (f" | {', '.join(apps)}" if apps else ""))
            lines += [f"    says: {row['comment']}" for row in same if row["comment"]]
    return "\n".join(lines)


def as_songs(rows):
    """The reports as SongCheck's songs: its reports are named by the report's number."""
    return [{"id": str(row["id"]), "title": row["title"], "artist": row["artist"], "lengthMs": row["length_ms"], "problem": row["problem"]} for row in rows if row["title"] and row["artist"]]


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
        lines.append(f"{row['id']} {row['problem']} {row['version']} | {row['title']} | {row['artist']} -> {now}")
    return "\n".join(lines)


def saved():
    if not SAVED.exists():
        sys.exit(f"no {SAVED}: run `pull` first")
    return json.loads(SAVED.read_text())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=["pull", "triage", "songs", "check"])
    parser.add_argument("--since", type=int, default=1, help="the first report's number")
    args = parser.parse_args(argv)
    if args.step == "pull":
        return pull()
    rows = since(saved(), args.since)
    if args.step == "triage":
        print(triage(rows))
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
