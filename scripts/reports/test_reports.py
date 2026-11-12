import json
import sys
import tempfile
import unicodedata
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import reports  # noqa: E402


def row(id, problem="no_lyrics", title="Made Up Song", artist="Some Band", version="1.0.0", android="16 (36)", app=None, comment=None, length_ms=None):
    return {"id": id, "problem": problem, "title": title, "artist": artist, "version": version, "android": android, "app": app, "comment": comment, "length_ms": length_ms}


def d1_answer(rows, counted=None, success=True):
    """What `cf d1 query` prints for COUNT then READ, with the rows of the second."""
    return json.dumps([
        {"results": [{"n": len(rows) if counted is None else counted}], "success": success},
        {"results": rows, "success": success},
    ])


def refusals_answer(refused, success=True):
    """What `cf d1 query` prints for REFUSED."""
    return json.dumps([{"results": refused, "success": success}])


REFUSED = [{"day": "2026-11-11", "why": "network", "n": 120}, {"day": "2026-11-10", "why": "day_full", "n": 3}]


class ReadTest(unittest.TestCase):
    def test_it_only_ever_asks_d1_to_read(self):
        for statement in (reports.READ, reports.COUNT, reports.REFUSED):
            self.assertRegex(statement, r"^select\b[^;]*$")

    def test_the_config_is_read_past_its_comments(self):
        text = '// the Worker\n{\n  // the account\n  "account_id": "acc",\n  "d1_databases": [{ "database_id": "db" }]\n}'
        self.assertEqual(reports.wrangler_config(text), ("db", "acc"))

    def test_the_table_is_the_rows_when_they_are_as_many_as_it_counts(self):
        self.assertEqual(reports.whole_table(d1_answer([row(1), row(2)])), [row(1), row(2)])
        self.assertEqual(reports.whole_table(d1_answer([])), [])

    def test_fewer_rows_than_the_table_counts_is_not_the_table(self):
        with self.assertRaisesRegex(ValueError, "counts 3 reports and gave 2"):
            reports.whole_table(d1_answer([row(1), row(2)], counted=3))

    def test_a_statement_d1_says_failed_is_not_the_table(self):
        with self.assertRaisesRegex(ValueError, "statement failed"):
            reports.whole_table(d1_answer([row(1)], success=False))

    def test_an_answer_of_another_shape_is_not_the_table(self):
        for answer in ('[{"results": [{"id": 1}], "success": true}]', '{"errors": []}', '[{"success": true}, {"success": true}]', "[]", "nothing"):
            with self.assertRaises(ValueError, msg=answer):
                reports.whole_table(answer)

    def test_the_refusals_are_the_rows_d1_gave(self):
        self.assertEqual(reports.refusals_of(refusals_answer(REFUSED)), REFUSED)
        for answer in (refusals_answer(REFUSED, success=False), d1_answer([row(1)]), '[{"success": true}]', "nothing"):
            with self.assertRaises(ValueError, msg=answer):
                reports.refusals_of(answer)

    def test_the_saved_reports_are_ignored_by_git(self):
        self.assertEqual(reports.OUT.relative_to(reports.ROOT).parts[0], "build")
        self.assertIn("build/", (reports.ROOT / ".gitignore").read_text().splitlines())


class PullTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.saved = Path(folder.name) / "reports.json"
        self.refused = Path(folder.name) / "refusals.json"
        for name, value in {"OUT": Path(folder.name), "SAVED": self.saved, "REFUSALS": self.refused}.items():
            patch = mock.patch.object(reports, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def pull(self, answer, env=None):
        with mock.patch.dict(reports.os.environ, env or {}), mock.patch("builtins.print"):
            reports.pull(mock.Mock(return_value=answer), sleep=mock.Mock())

    def test_pull_saves_the_rows_and_the_refusals_and_tells_cf_the_account(self):
        run = mock.Mock(side_effect=[mock.Mock(returncode=0, stdout=d1_answer([row(1)])), mock.Mock(returncode=0, stdout=refusals_answer(REFUSED))])
        with mock.patch("builtins.print"):
            reports.pull(run, sleep=mock.Mock())
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])
        self.assertEqual(json.loads(self.refused.read_text()), REFUSED)
        self.assertEqual(sorted(path.name for path in self.saved.parent.iterdir()), ["refusals.json", "reports.json"])
        (command, kwargs), (refusals, _) = run.call_args_list
        self.assertEqual(command[0][3:5], ["d1", "query"])
        self.assertEqual(command[0][-1], f"{reports.COUNT}; {reports.READ}")
        self.assertEqual(refusals[0][-1], reports.REFUSED)
        self.assertIn("CLOUDFLARE_ACCOUNT_ID", kwargs["env"])

    def test_refusals_not_read_still_leave_the_reports_and_say_so(self):
        # As before limits.sql is run on the live D1, which has no refusals table yet.
        self.refused.write_text(json.dumps(REFUSED))
        no_table = mock.Mock(returncode=1, stderr="no such table: refusals")
        run, sleep = mock.Mock(side_effect=[mock.Mock(returncode=0, stdout=d1_answer([row(1)])), no_table, no_table, no_table]), mock.Mock()
        with mock.patch("builtins.print") as said:
            reports.pull(run, sleep)
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])
        self.assertFalse(self.refused.exists())
        self.assertIn("no such table: refusals", str(said.call_args_list))
        # An answer of another shape is the same.
        run = mock.Mock(side_effect=[mock.Mock(returncode=0, stdout=d1_answer([row(1)])), mock.Mock(returncode=0, stdout="nothing")])
        with mock.patch("builtins.print"):
            reports.pull(run, sleep)
        self.assertFalse(self.refused.exists())

    def test_a_pull_leaves_out_where_lines_were_moved(self):
        self.pull(mock.Mock(returncode=0, stdout=d1_answer([{**row(1), "timing": "[]"}])))
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])

    def test_a_pull_replaces_the_last_ones_table(self):
        self.saved.write_text(json.dumps([row(1)]))
        self.pull(mock.Mock(returncode=0, stdout=d1_answer([row(1), row(2)])))
        self.assertEqual(json.loads(self.saved.read_text()), [row(1), row(2)])

    def test_a_failed_pull_stops_says_why_and_leaves_no_table(self):
        self.saved.write_text(json.dumps([row(1)]))
        self.refused.write_text(json.dumps(REFUSED))
        with self.assertRaisesRegex(SystemExit, "login expired") as stopped:
            self.pull(mock.Mock(returncode=1, stderr="login expired"))
        self.assertIn("no reports were pulled", str(stopped.exception))
        self.assertEqual(list(self.saved.parent.iterdir()), [])

    def test_a_refusal_that_passes_is_asked_again_and_the_table_saved(self):
        refused = mock.Mock(returncode=1, stderr="Account is invalid or unauthorized [code: 7403]")
        run, sleep = mock.Mock(side_effect=[refused, refused, mock.Mock(returncode=0, stdout=d1_answer([row(1)])), mock.Mock(returncode=0, stdout=refusals_answer([]))]), mock.Mock()
        with mock.patch("builtins.print"):
            reports.pull(run, sleep)
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])
        self.assertEqual(run.call_count, 4)
        self.assertEqual([wait.args[0] for wait in sleep.call_args_list], list(reports.WAITS))

    def test_a_refusal_that_stays_is_asked_three_times_then_stops(self):
        run, sleep = mock.Mock(return_value=mock.Mock(returncode=1, stderr="[code: 7403]")), mock.Mock()
        with mock.patch("builtins.print"), self.assertRaisesRegex(SystemExit, "7403"):
            reports.pull(run, sleep)
        self.assertEqual(run.call_count, 3)
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(list(self.saved.parent.iterdir()), [])

    def test_a_failed_pull_exits_non_zero(self):
        with self.assertRaises(SystemExit) as stopped:
            self.pull(mock.Mock(returncode=1, stderr="403 Forbidden"))
        self.assertNotIn(stopped.exception.code, (0, None))

    def test_a_token_in_the_environment_is_named_when_cf_is_refused(self):
        refused = mock.Mock(returncode=1, stderr="[10000] Authentication error\n403 Forbidden")
        with self.assertRaisesRegex(SystemExit, "CLOUDFLARE_API_TOKEN is set here"):
            self.pull(refused, {"CLOUDFLARE_API_TOKEN": "not-a-real-token"})
        # Not named when there is none, or when the refusal is something else.
        with self.assertRaises(SystemExit) as stopped:
            with mock.patch.dict(reports.os.environ, clear=True):
                self.pull(refused)
        self.assertNotIn("CLOUDFLARE_API_TOKEN", str(stopped.exception))
        with self.assertRaises(SystemExit) as stopped:
            self.pull(mock.Mock(returncode=1, stderr="429 Too Many Requests"), {"CLOUDFLARE_API_TOKEN": "not-a-real-token"})
        self.assertNotIn("CLOUDFLARE_API_TOKEN", str(stopped.exception))

    def test_an_answer_that_is_not_the_whole_table_leaves_no_table(self):
        self.saved.write_text(json.dumps([row(1)]))
        with self.assertRaisesRegex(SystemExit, "not with the whole table.*counts 5 reports and gave 1"):
            self.pull(mock.Mock(returncode=0, stdout=d1_answer([row(1)], counted=5)))
        self.assertEqual(list(self.saved.parent.iterdir()), [])
        with self.assertRaisesRegex(SystemExit, "not with the whole table"):
            self.pull(mock.Mock(returncode=0, stdout=""))
        self.assertEqual(list(self.saved.parent.iterdir()), [])


class TriageTest(unittest.TestCase):
    def test_since_keeps_that_report_on(self):
        self.assertEqual([r["id"] for r in reports.since([row(38), row(39), row(40)], 39)], [39, 40])

    def test_one_song_however_it_is_written(self):
        self.assertEqual(reports.song_key(row(1, title="Canción (feat. Someone Else)", artist="The BAND")), reports.song_key(row(2, title="cancion", artist="the band")))

    def test_different_songs_stay_apart(self):
        self.assertNotEqual(reports.song_key(row(1, title="One")), reports.song_key(row(2, title="Two")))

    def test_groups_by_problem_most_first_then_version_and_song(self):
        text = reports.triage([
            row(1, problem="no_chords"),
            row(2, title="Other"), row(3, title="Other (feat. Guest)", app="Player", version="2.0.0"), row(4, title="Third"),
        ])
        self.assertLess(text.index("no_lyrics: 3, 1 naming the app"), text.index("no_chords: 1, 0 naming the app"))
        self.assertIn("1.0.0 on Android 16 (36) x2, 2.0.0 on Android 16 (36) x1", text)
        self.assertIn("2, 3: Other | Some Band | Player", text)
        self.assertIn("4: Third | Some Band", text)

    def test_a_report_by_hand_is_shown_with_what_it_says(self):
        text = reports.triage([row(9, problem=None, comment="words are wrong")])
        self.assertIn("by hand: 1", text)
        self.assertIn("says: words are wrong", text)

    def test_a_wrong_reading_shows_the_word_how_it_read_and_the_right_one(self):
        marked = {**row(10, problem="wrong_reading"), "word": "花", "reading_shown": "か / ka", "right_reading": "はな", "reader": "ipadic+unidic", "language": "japanese", "lyric_line": "空の花"}
        text = reports.triage([marked, {**marked, "id": 11, "right_reading": None, "reader": None, "language": None, "lyric_line": None}])
        self.assertIn("wrong_reading: 2", text)
        self.assertIn("    花: か / ka -> はな (ipadic+unidic, japanese)\n      in: 空の花", text)
        self.assertTrue(text.endswith("    花: か / ka"))

    def test_timing_kept_from_before_says_how_many_were_heard_sung_and_names_the_recording(self):
        kept = [dict(row(1, problem="kept_timing"), sung=1, recording="SPOTIFY:made-up"), dict(row(2, problem="kept_timing", title="Other"), sung=0)]
        text = reports.triage(kept)
        self.assertIn("kept_timing: 2, 0 naming the app\n  1 with the singing heard through it\n  1.0.0 on Android 16 (36) x2", text)
        self.assertIn("1: Made Up Song | Some Band | SPOTIFY:made-up", text)
        self.assertIn("2: Other | Some Band\n", text + "\n")

    def test_no_reports_is_said_plainly(self):
        self.assertEqual(reports.triage([], []), "0 reports\n0 turned away")


class CheckTest(unittest.TestCase):
    def test_songs_are_named_by_the_report_and_keep_their_length(self):
        self.assertEqual(
            reports.as_songs([row(7, length_ms=1000), row(8, title=None)]),
            [{"id": "7", "title": "Made Up Song", "artist": "Some Band", "lengthMs": 1000, "problem": "no_lyrics"}],
        )

    def test_songs_for_songcheck_carry_no_character_a_terminal_acts_on(self):
        # SongCheck prints each song's title and artist as it goes, and rows already in D1 weren't cleaned.
        songs = reports.as_songs([row(7, title=f"Made\tUp{NASTY}", artist="Some\nBand\u200d\U0001f3b8")])
        self.assertEqual(songs[0]["title"], "Made Upa[2J]0;titleb cde f ghijklm nopqrstuvwxyz")
        self.assertEqual(songs[0]["artist"], "Some Band\U0001f3b8")
        for song in songs:
            self.assertEqual([], unseen(song["title"] + song["artist"]))
        # Nothing left is no song to look for.
        self.assertEqual(reports.as_songs([row(8, title="\x1b\u200b")]), [])

    def test_what_the_finders_returned_in_few_words(self):
        self.assertEqual(reports.now_found({"lyrics": {"kind": "none"}}), "none")
        self.assertEqual(reports.now_found({"lyrics": {"kind": "instrumental"}}), "instrumental")
        lyrics = {"kind": "found", "source": "lrclib", "lines": [{}, {}]}
        self.assertEqual(reports.now_found({"lyrics": lyrics, "chords": {"kind": "none"}}), "found on lrclib, 2 timed lines; chords: none")
        self.assertEqual(
            reports.now_found({"lyrics": lyrics, "chords": {"kind": "found", "site": "UFRET", "url": "http://x"}}),
            "found on lrclib, 2 timed lines; chords on UFRET: http://x",
        )
        self.assertEqual(reports.now_found({"lyrics": lyrics, "chordsError": "boom"}), "found on lrclib, 2 timed lines; chords: boom")

    def test_a_report_not_run_says_so(self):
        with tempfile.TemporaryDirectory() as folder:
            done = Path(folder) / "reports"
            done.mkdir()
            (done / "1.json").write_text(json.dumps({"lyrics": {"kind": "none"}}))
            text = reports.check([row(1), row(2)], Path(folder))
        self.assertIn("1 no_lyrics 1.0.0 | Made Up Song | Some Band -> none", text)
        self.assertIn("2 no_lyrics 1.0.0 | Made Up Song | Some Band -> not run", text)


class RefusedTest(unittest.TestCase):
    def test_triage_shows_the_reports_turned_away_by_day_and_why_newest_first(self):
        days = [{"day": f"2026-10-{day:02}", "why": "network", "n": 1} for day in range(1, 21)]
        text = reports.triage([row(1)], REFUSED + days)
        lines = text.splitlines()
        self.assertEqual(lines[1], "143 turned away")
        self.assertEqual(lines[2], "  2026-11-11: network 120")
        self.assertEqual(lines[3], "  2026-11-10: day_full 3")
        self.assertEqual(lines[4], "  2026-10-20: network 1")
        # Two weeks of days, and the rest in the count.
        self.assertIn("2026-10-09", text)
        self.assertNotIn("2026-10-08", text)

    def test_two_reasons_on_one_day_share_its_line(self):
        refused = [{"day": "2026-11-11", "why": "day_full", "n": 2}, {"day": "2026-11-11", "why": "network", "n": 5}]
        self.assertIn("  2026-11-11: network 5, day_full 2", reports.triage([], refused))

    def test_triage_says_when_none_were_turned_away_or_they_were_not_read(self):
        self.assertEqual(reports.triage([], []).splitlines()[1], "0 turned away")
        self.assertEqual(reports.triage([], None).splitlines()[1], "turned away: not read, pull again")


class MainTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        out = Path(folder.name)
        for name, value in {"OUT": out, "SAVED": out / "reports.json", "REFUSALS": out / "refusals.json", "CHECK": out / "check"}.items():
            patch = mock.patch.object(reports, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def test_steps_other_than_pull_want_the_reports_pulled(self):
        with self.assertRaisesRegex(SystemExit, "run `pull` first"):
            reports.main(["triage"])

    def test_triage_shows_the_refusals_pulled(self):
        reports.SAVED.write_text(json.dumps([row(39)]))
        reports.REFUSALS.write_text(json.dumps(REFUSED))
        with mock.patch("builtins.print") as said:
            reports.main(["triage"])
        self.assertIn("123 turned away", said.call_args.args[0])

    def test_pull_is_a_step(self):
        with mock.patch.object(reports, "pull") as pull:
            reports.main(["pull"])
        pull.assert_called_once()

    def test_each_step_goes_from_the_report_asked_for(self):
        reports.SAVED.write_text(json.dumps([row(38), row(39)]))
        with mock.patch("builtins.print") as said:
            reports.main(["triage", "--since", "39"])
            reports.main(["songs", "--since", "39"])
            reports.main(["check", "--since", "39"])
        self.assertIn("39: Made Up Song", said.call_args_list[0].args[0])
        self.assertNotIn("38", said.call_args_list[0].args[0])
        self.assertEqual([song["id"] for song in json.loads((reports.CHECK / "songs.json").read_text())], ["39"])
        self.assertIn("39 no_lyrics", said.call_args_list[2].args[0])

    def test_a_new_song_list_drops_the_last_runs_answers(self):
        # SongCheck goes on from the answers it finds, so an old one would pass for what the finders say now.
        reports.SAVED.write_text(json.dumps([row(39)]))
        old = reports.CHECK / "reports" / "39.json"
        old.parent.mkdir(parents=True)
        old.write_text(json.dumps({"lyrics": {"kind": "none"}}))
        with mock.patch("builtins.print") as said:
            reports.main(["songs", "--since", "39"])
            reports.main(["check", "--since", "39"])
        self.assertFalse(old.exists())
        self.assertIn("-> not run", said.call_args_list[1].args[0])


# Made up: what a stranger could write in a report to move the cursor, recolor or retitle the terminal, or turn text around.
# Past the control characters: zero-width ones, a tag, a mark that turns text around, a private one and half a pair.
NASTY = "a\x1b[2J\x1b]0;title\x07b\x00\x7f\x85\x9bc\u202ed\u2066e\u2069\u2028f\tg\u200bh\u200di\U000e0041j\u200ek\ue000l\ud800m\u2029n\u061co\ufeffp\u0378q\u034fr\ufe0fs\ufe00t\U000e0100u\u115fv\u1160w\u3164x\uffa0y\u2800z"


# Characters that print as nothing, or as a blank, though Unicode files them as marks or letters: the grapheme joiner,
# the variation selectors, the Hangul fillers and the blank braille cell. Each can hide in a title or pass for a space.
BLANKS = {0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, *range(0xFE00, 0xFE10), *range(0xE0100, 0xE01F0)}


def unseen(text):
    """The characters a terminal acts on, hides or can't print, apart from the line break the output is made of."""
    return [c for c in text if c != "\n" and (unicodedata.category(c) in ("Cc", "Cf", "Zl", "Zp", "Co", "Cs", "Cn") or ord(c) in BLANKS)]


class ShownTest(unittest.TestCase):
    def test_control_characters_and_turning_marks_are_shown_escaped(self):
        self.assertEqual(
            reports.shown(NASTY),
            "a\\x1b[2J\\x1b]0;title\\x07b\\x00\\x7f\\x85\\x9bc\\u202ed\\u2066e\\u2069\\u2028f\\tg"
            "\\u200bh\\u200di\\U000e0041j\\u200ek\\ue000l\\ud800m\\u2029n\\u061co\\ufeffp"
            "\\u0378q\\u034fr\\ufe0fs\\ufe00t\\U000e0100u\\u115fv\\u1160w\\u3164x\\uffa0y\\u2800z",
        )
        # An emoji keeps its picture: only its unseen style mark is written out, after it.
        self.assertEqual(reports.shown("Love \u2764\ufe0f song \U0001f3b8"), "Love \u2764\\ufe0f song \U0001f3b8")
        self.assertEqual(reports.shown("line\nbreak\r"), "line\\nbreak\\r")
        self.assertEqual(reports.shown("Demo 花 أغنية"), "Demo 花 أغنية")
        # Every character of what was printed can be printed: half a pair would stop Python's print.
        reports.shown(NASTY).encode("utf-8")
        self.assertEqual(reports.shown(None), None)
        self.assertEqual(reports.shown(7), 7)

    def test_every_step_that_prints_prints_no_control_character(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            nasty = {
                **row(39, problem="wrong_reading", title=NASTY, artist=NASTY, version=NASTY, android=NASTY, app=NASTY, comment=f"one\n  40: Fake | Song{NASTY}"),
                "recording": NASTY, "word": NASTY, "reading_shown": NASTY, "right_reading": NASTY, "reader": NASTY, "language": NASTY, "lyric_line": NASTY,
            }
            (out / "reports.json").write_text(json.dumps([nasty, {**nasty, "id": 40, "problem": NASTY}]))
            (out / "refusals.json").write_text(json.dumps([{"day": NASTY, "why": NASTY, "n": 1}]))
            (out / "check" / "reports").mkdir(parents=True)
            # What the finders found comes from other sites, so it's a stranger's text too.
            found = {"lyrics": {"kind": "found", "source": NASTY, "lines": []}, "chords": {"kind": "found", "site": NASTY, "url": NASTY}}
            (out / "check" / "reports" / "39.json").write_text(json.dumps(found))
            (out / "check" / "reports" / "40.json").write_text(json.dumps({"lyrics": {"kind": NASTY}}))
            with mock.patch.object(reports, "OUT", out), mock.patch.object(reports, "SAVED", out / "reports.json"), mock.patch.object(reports, "REFUSALS", out / "refusals.json"), mock.patch.object(reports, "CHECK", out / "check"):
                with mock.patch("builtins.print") as said:
                    reports.main(["triage"])
                    reports.main(["check"])
                    reports.main(["songs"])
        printed = [str(arg) for call in said.call_args_list for arg in call.args]
        self.assertEqual(len(printed), 3)
        for text in printed:
            self.assertEqual([], unseen(text), repr(text))
        # A comment's lines stay in its own line, so it can't pass for a report of its own.
        self.assertIn("    says: one\\n  40: Fake | Song", printed[0])
        self.assertIn("\\x1b[2J", printed[0])
        self.assertIn("found on a\\x1b[2J", printed[1])


if __name__ == "__main__":
    unittest.main()
