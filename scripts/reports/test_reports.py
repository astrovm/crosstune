import json
import sys
import tempfile
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


class ReadTest(unittest.TestCase):
    def test_it_only_ever_asks_d1_to_read(self):
        for statement in (reports.READ, reports.COUNT):
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

    def test_the_saved_reports_are_ignored_by_git(self):
        self.assertEqual(reports.OUT.relative_to(reports.ROOT).parts[0], "build")
        self.assertIn("build/", (reports.ROOT / ".gitignore").read_text().splitlines())


class PullTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.saved = Path(folder.name) / "reports.json"
        for name, value in {"OUT": Path(folder.name), "SAVED": self.saved}.items():
            patch = mock.patch.object(reports, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def pull(self, answer, env=None):
        with mock.patch.dict(reports.os.environ, env or {}), mock.patch("builtins.print"):
            reports.pull(mock.Mock(return_value=answer), sleep=mock.Mock())

    def test_pull_saves_the_rows_and_tells_cf_the_account(self):
        run = mock.Mock(return_value=mock.Mock(returncode=0, stdout=d1_answer([row(1)])))
        with mock.patch("builtins.print"):
            reports.pull(run, sleep=mock.Mock())
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])
        self.assertEqual([path.name for path in self.saved.parent.iterdir()], ["reports.json"])
        command, kwargs = run.call_args
        self.assertEqual(command[0][3:5], ["d1", "query"])
        self.assertEqual(command[0][-1], f"{reports.COUNT}; {reports.READ}")
        self.assertIn("CLOUDFLARE_ACCOUNT_ID", kwargs["env"])

    def test_a_pull_replaces_the_last_ones_table(self):
        self.saved.write_text(json.dumps([row(1)]))
        self.pull(mock.Mock(returncode=0, stdout=d1_answer([row(1), row(2)])))
        self.assertEqual(json.loads(self.saved.read_text()), [row(1), row(2)])

    def test_a_failed_pull_stops_says_why_and_leaves_no_table(self):
        self.saved.write_text(json.dumps([row(1)]))
        with self.assertRaisesRegex(SystemExit, "login expired") as stopped:
            self.pull(mock.Mock(returncode=1, stderr="login expired"))
        self.assertIn("no reports were pulled", str(stopped.exception))
        self.assertEqual(list(self.saved.parent.iterdir()), [])

    def test_a_refusal_that_passes_is_asked_again_and_the_table_saved(self):
        refused = mock.Mock(returncode=1, stderr="Account is invalid or unauthorized [code: 7403]")
        run, sleep = mock.Mock(side_effect=[refused, refused, mock.Mock(returncode=0, stdout=d1_answer([row(1)]))]), mock.Mock()
        with mock.patch("builtins.print"):
            reports.pull(run, sleep)
        self.assertEqual(json.loads(self.saved.read_text()), [row(1)])
        self.assertEqual(run.call_count, 3)
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

    def test_timing_kept_from_before_says_how_many_were_heard_sung_and_names_the_recording(self):
        kept = [dict(row(1, problem="kept_timing"), sung=1, recording="SPOTIFY:made-up"), dict(row(2, problem="kept_timing", title="Other"), sung=0)]
        text = reports.triage(kept)
        self.assertIn("kept_timing: 2, 0 naming the app\n  1 with the singing heard through it\n  1.0.0 on Android 16 (36) x2", text)
        self.assertIn("1: Made Up Song | Some Band | SPOTIFY:made-up", text)
        self.assertIn("2: Other | Some Band\n", text + "\n")

    def test_no_reports_is_said_plainly(self):
        self.assertEqual(reports.triage([]), "0 reports")


class CheckTest(unittest.TestCase):
    def test_songs_are_named_by_the_report_and_keep_their_length(self):
        self.assertEqual(
            reports.as_songs([row(7, length_ms=1000), row(8, title=None)]),
            [{"id": "7", "title": "Made Up Song", "artist": "Some Band", "lengthMs": 1000, "problem": "no_lyrics"}],
        )

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


class MainTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        out = Path(folder.name)
        for name, value in {"OUT": out, "SAVED": out / "reports.json", "CHECK": out / "check"}.items():
            patch = mock.patch.object(reports, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def test_steps_other_than_pull_want_the_reports_pulled(self):
        with self.assertRaisesRegex(SystemExit, "run `pull` first"):
            reports.main(["triage"])

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


if __name__ == "__main__":
    unittest.main()
