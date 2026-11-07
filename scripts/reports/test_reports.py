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


class ReadTest(unittest.TestCase):
    def test_it_only_ever_asks_d1_to_read(self):
        self.assertRegex(reports.READ, r"^select\b[^;]*$")

    def test_the_config_is_read_past_its_comments(self):
        text = '// the Worker\n{\n  // the account\n  "account_id": "acc",\n  "d1_databases": [{ "database_id": "db" }]\n}'
        self.assertEqual(reports.wrangler_config(text), ("db", "acc"))

    def test_the_rows_of_each_statement_come_out_as_one_list(self):
        self.assertEqual(reports.rows_in('[{"results": [{"id": 1}]}, {"results": [{"id": 2}]}]'), [{"id": 1}, {"id": 2}])

    def test_pull_saves_only_under_build_and_tells_cf_the_account(self):
        answer = mock.Mock(returncode=0, stdout=json.dumps([{"results": [row(1)]}]))
        run = mock.Mock(return_value=answer)
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(reports, "OUT", Path(folder)), mock.patch.object(reports, "SAVED", Path(folder) / "reports.json"), mock.patch("builtins.print"):
            reports.pull(run)
            self.assertEqual(json.loads((Path(folder) / "reports.json").read_text()), [row(1)])
        command, kwargs = run.call_args
        self.assertEqual(command[0][3:5], ["d1", "query"])
        self.assertIn("CLOUDFLARE_ACCOUNT_ID", kwargs["env"])

    def test_a_failed_pull_stops_and_says_why(self):
        run = mock.Mock(return_value=mock.Mock(returncode=1, stderr="login expired"))
        with self.assertRaisesRegex(SystemExit, "login expired"):
            reports.pull(run)

    def test_the_saved_reports_are_ignored_by_git(self):
        self.assertEqual(reports.OUT.relative_to(reports.ROOT).parts[0], "build")
        self.assertIn("build/", (reports.ROOT / ".gitignore").read_text().splitlines())


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


if __name__ == "__main__":
    unittest.main()
