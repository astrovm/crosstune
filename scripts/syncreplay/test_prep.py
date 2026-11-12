import argparse
from array import array
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch

# A shared folder set for real replays (SYNCREPLAY_DIR) would have the tests read and write the real ones.
os.environ.pop('SYNCREPLAY_DIR', None)
spec = importlib.util.spec_from_file_location('replay', Path(__file__).with_name('syncreplay.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

class ResumeTest(unittest.TestCase):
    def test_truth_without_hearing_is_resumed(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'set.json').write_text(json.dumps([dict(id='fake', lines=1, title='Song', artist='Artist')]))
            (r.OUT / 'fake.truth.jsonl').write_text('{}\n')
            with patch.object(r, 'prep') as prep, patch.object(r.subprocess, 'run', return_value=argparse.Namespace(returncode=0)):
                r.find_set(argparse.Namespace(cached=True, find_only=False, gradle="fake-gradle", at_once=1))
            prep.assert_called_once()

    def test_songs_can_be_prepped_at_once_and_each_failure_is_named(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'set.json').write_text(json.dumps([dict(id=name, lines=1, title='Song', artist='Artist') for name in ('one', 'two', 'three')]))
            both = threading.Barrier(2, timeout=5)

            def prep(args):
                if args.id == 'three':
                    raise OSError('nope')
                both.wait()

            with patch.object(r, 'prep', side_effect=prep), patch('sys.stdout'), patch('sys.stderr'), self.assertRaises(SystemExit) as stopped:
                r.find_set(argparse.Namespace(cached=True, find_only=False, gradle='fake-gradle', at_once=2))
            self.assertIn('three', str(stopped.exception))
            self.assertNotIn('one', str(stopped.exception))

    def test_hearing_alone_does_not_make_song_prepped(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'fake.truth.jsonl').write_text('{}\n')
            (r.OUT / 'fake.heard20.json').write_text('{}')
            self.assertEqual([], r.prepped())

class WordsForTheRecordingTest(unittest.TestCase):
    """Words are found again once the recordings are in, as a phone knows how long what it plays is."""

    def found(self, cached, prepped):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            def gradle(*_, **__):
                (r.OUT / 'set.json').write_text(json.dumps([dict(id='fake', lines=1, title='Song', artist='Artist')]))
                return argparse.Namespace(returncode=0)

            if cached:
                gradle()
            with patch.object(r.subprocess, 'run', side_effect=gradle) as finding, patch.object(r, 'prep'), patch.object(r, 'complete', return_value=not prepped), patch('sys.stdout'):
                r.find_set(argparse.Namespace(cached=cached, find_only=False, gradle='fake-gradle', at_once=1))
            return finding.call_count

    def test_a_song_just_prepped_has_its_words_found_again(self):
        self.assertEqual(self.found(cached=False, prepped=True), 2)

    def test_with_nothing_new_they_are_found_once(self):
        self.assertEqual(self.found(cached=False, prepped=False), 1)

    def test_resuming_what_was_found_finds_them_once_it_is_all_in(self):
        self.assertEqual(self.found(cached=True, prepped=True), 1)

    def test_resuming_with_nothing_left_finds_nothing(self):
        self.assertEqual(self.found(cached=True, prepped=False), 0)


class PartTest(unittest.TestCase):
    """--part takes a part of the songs, fixed by each song's ID, so a change tuned on some is checked on the rest."""

    songs = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

    def test_each_song_is_in_one_part_by_its_id(self):
        parts = [r.part(self.songs, r.part_arg(f'{k}/3')) for k in (1, 2, 3)]
        self.assertEqual(parts, [['g', 'h'], ['a', 'c', 'd', 'e'], ['b', 'f']])
        self.assertEqual(r.part(self.songs, r.part_arg('1/1')), self.songs)

    def test_a_songs_part_stays_as_songs_are_added(self):
        held = r.part(self.songs, r.part_arg('held'))
        self.assertEqual(r.part(self.songs + ['i', 'j', 'k'], r.part_arg('held'))[:len(held)], held)

    def test_the_held_out_songs_are_none_of_those_tuned_on(self):
        held = r.part(self.songs, r.part_arg('held'))
        tune = r.part(self.songs, r.part_arg('tune'))
        self.assertEqual(held, ['g', 'h'])
        self.assertEqual(sorted(held + tune), self.songs)
        self.assertEqual(r.part(self.songs, r.part_arg('1/3')), held)

    def test_with_no_part_every_song_is_taken(self):
        self.assertEqual(r.part(['a', 'b'], None), ['a', 'b'])

    def test_a_part_given_wrong_is_refused_plainly(self):
        for which in ('0/3', '4/3', '1/0', 'x', '1/', '/3', '1/2/3', '-1/3', 'held/3'):
            err = io.StringIO()
            with self.subTest(which=which), patch('sys.argv', ['syncreplay.py', 'untimed', f'--part={which}']), \
                    patch.object(r, 'untimed') as untimed, contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as stop:
                r.main()
            self.assertEqual(stop.exception.code, 2)
            self.assertIn(f"argument --part: {which!r} is not held, tune or K/N", err.getvalue())
            self.assertNotIn('Traceback', err.getvalue())
            untimed.assert_not_called()

    def test_untimed_replays_only_the_part_asked_for(self):
        with tempfile.TemporaryDirectory() as directory:
            heard = Path(directory) / 'heard.json'
            heard.write_text(json.dumps({'0': [], '20000': []}))
            given = []
            with patch.object(r, 'prepped', return_value=self.songs), patch.object(r, 'heard_file', return_value=heard), \
                    patch.object(r, 'untimed_input', side_effect=lambda song, *_: (given.append(song), (Path(directory) / f'{song}.tsv', []))[1]), \
                    patch.object(r, 'untimed_classes', return_value=Path('classes')), patch.object(r, 'kotlin_jars', return_value=[Path('kotlin-stdlib-2.jar')]), \
                    patch.object(r.subprocess, 'run') as run, patch.object(r, 'untimed_score') as score, \
                    patch('sys.argv', ['syncreplay.py', 'untimed', '--nearest', '--part', 'held']):
                r.main()
        self.assertEqual(given, ['g', 'h'])
        self.assertEqual(score.call_args.args[0], ['g', 'h'])
        self.assertEqual(run.call_args.args[0][-2:], [f'{directory}/g.tsv', f'{directory}/h.tsv'])


class UntimedScoreTest(unittest.TestCase):
    """Each line scored against when it's sung: put near it, put off, put where nothing was heard, or shown wrong along the way."""

    def test_each_line_is_counted_where_it_was_put(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            out = Path(directory)
            # Lines 0 to 4 sung at 2, 5, 8, 11 and 50 s; the while heard is the first 20 s.
            (out / 'a.truth.jsonl').write_text(''.join(json.dumps(dict(line=n, start=at)) + '\n' for n, at in enumerate((2, 5, 8, 11, 50))))
            (out / 'a.language').write_text('en\n')
            path = out / 'a.tsv'
            # First put line 1 4 s late, then where it's sung; line 2 0.3 s off, line 3 2 s off, line 4 at 60 s, past what was heard.
            Path(f'{path}.placed').write_text('R\t1\n1\t9000\nR\t2\n1\t5000\n2\t8300\n3\t13000\n4\t60000\n')
            with contextlib.redirect_stdout(io.StringIO()) as shown:
                rows = r.untimed_score(['a'], {'a': (path, [0])})
        # Lines 1 and 2 end within 1 s; 1 to 4 end put.
        self.assertEqual(rows['a'], dict(heard=4, timed=3, on=2, close=2, off=2, far=1, unheard=1, wrong=3, right=[1, 2], put=[1, 2, 3, 4]))
        self.assertIn(' off >3 s unheard wrong ', shown.getvalue())


class AgainstMainTest(unittest.TestCase):
    """Each run held to the lines main put within 1 s, run the same way: right on main and wrong or dark now."""

    def run_as(self, **given):
        return argparse.Namespace(**{**dict(start=0, work=4500, again=None, heard='', nearest=False, write_main=False, verbose=False), **given})

    def test_a_run_is_kept_by_how_it_was_run(self):
        self.assertEqual(r.untimed_key(self.run_as()), 'from 0 work 4500')
        self.assertEqual(r.untimed_key(self.run_as(again=12000, heard='.other', nearest=True)), 'from 0 work 4500 again 12000 heard .other nearest True')

    def test_lines_right_on_main_are_wrong_dark_or_still_right(self):
        # Main had lines 1, 2 and 5 right; now 1 is right, 2 put off, 5 dark, and 7 right that main didn't have.
        rows = dict(a=dict(right=[1, 7], put=[1, 2, 7]), b=dict(right=[0], put=[0]))
        self.assertEqual(r.against_main(dict(a=[1, 2, 5]), rows), ([('a', 2)], [('a', 5)], [('a', 7)]))

    def test_main_is_kept_then_held_to(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'UNTIMED_MAIN', Path(directory) / 'main.json'):
            r.held_to_main(self.run_as(write_main=True), dict(a=dict(right=[1, 2], put=[1, 2])))
            r.held_to_main(self.run_as(write_main=True, again=12000), dict(a=dict(right=[3], put=[3])))
            self.assertEqual(json.loads(r.UNTIMED_MAIN.read_text()), {'from 0 work 4500': dict(a=[1, 2]), 'from 0 work 4500 again 12000': dict(a=[3])})
            with contextlib.redirect_stdout(io.StringIO()) as shown:
                r.held_to_main(self.run_as(verbose=True), dict(a=dict(right=[2], put=[1, 2])))
            self.assertEqual(shown.getvalue(), 'right on main, wrong now: 1  a:1\nright on main, dark now: 0\nnot right on main, right now: 0\n')
            # Run some other way, nothing is kept to hold it to.
            with contextlib.redirect_stdout(io.StringIO()) as shown:
                r.held_to_main(self.run_as(work=3000), dict(a=dict(right=[], put=[1])))
            self.assertEqual(shown.getvalue(), '')

    def test_another_timing_takes_the_checkouts_place(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            other = Path(directory) / 'main.kt'
            other.write_text('main')
            sources = r.timing_copied(other, [Path('SungTiming.kt'), Path('SyncedLyrics.kt')])
            self.assertEqual(sources, [Path(directory) / 'other-timing' / 'SungTiming.kt', Path('SyncedLyrics.kt')])
            self.assertEqual(sources[0].read_text(), 'main')


class SharedFolderTest(unittest.TestCase):
    """SYNCREPLAY_DIR keeps the inputs for every checkout, while what one checkout asks and gets stays its own."""

    def loaded(self, **env):
        with patch.dict(os.environ, env):
            if not env:
                os.environ.pop('SYNCREPLAY_DIR', None)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        return module

    def test_by_default_everything_is_in_the_checkout(self):
        module = self.loaded()
        self.assertEqual(module.OUT, module.ROOT / 'build' / 'syncreplay')
        with patch.dict(os.environ):
            os.environ.pop('SYNCREPLAY_DIR', None)
            self.assertEqual(module.own(), module.OUT)

    def test_the_folder_can_be_chosen(self):
        module = self.loaded(SYNCREPLAY_DIR='/somewhere/kept')
        self.assertEqual(module.OUT, Path('/somewhere/kept'))
        self.assertEqual(module.HEARD, Path('/somewhere/kept/heard'))
        with patch.dict(os.environ, SYNCREPLAY_DIR='/somewhere/kept'):
            self.assertEqual(module.own(), module.ROOT / 'build' / 'syncreplay')

    def test_replays_of_two_checkouts_ask_apart_and_read_their_own_results(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, SYNCREPLAY_DIR=directory):
            kept, mine = Path(directory) / 'kept', Path(directory) / 'mine'
            kept.mkdir()
            mine.mkdir()
            seen = []

            def gradle(*_, **__):
                seen.append((sorted(f.name for f in mine.iterdir() if f.name.startswith('replay')), (kept / 'replay.json').exists()))
                (mine / 'fake.from0.shown.json').write_text(json.dumps(dict(lines=['One'], shown=[dict(line=0, at=1000)], heard=[])))
                return argparse.Namespace(returncode=0)

            (kept / 'fake.lyrics.json').write_text(json.dumps([[0, 'One']]))
            (kept / 'fake.truth.jsonl').write_text(json.dumps(dict(line=0, start=1.0)) + '\n')
            with patch.object(r, 'OUT', kept), patch.object(r, 'own', return_value=mine), patch.object(r, 'prepped', return_value=['fake']), \
                    patch.object(r.subprocess, 'run', side_effect=gradle), patch('sys.stdout', io.StringIO()) as table:
                r.run_all(argparse.Namespace(gradle='fake-gradle', work=4500, behind=0, heard='.other'))
            self.assertEqual(seen, [(['replay.json', 'replay.songs'], False)])
            self.assertRegex(table.getvalue(), r'all +1 +1 +1 +0 +0')
            self.assertFalse((kept / 'fake.from0.shown.json').exists())

    def test_finding_the_set_asks_in_the_checkout_and_its_words_are_kept_in_the_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            kept, mine = Path(directory) / 'kept', Path(directory) / 'mine'
            kept.mkdir()
            mine.mkdir()
            asked = []

            def gradle(*_, **__):
                asked.append((mine / 'set.asked').exists())
                (kept / 'set.json').write_text('[]')
                return argparse.Namespace(returncode=0)

            with patch.object(r, 'OUT', kept), patch.object(r, 'own', return_value=mine), patch.object(r.subprocess, 'run', side_effect=gradle):
                r.find_set(argparse.Namespace(cached=False, find_only=True, gradle='fake-gradle'))
            self.assertEqual(asked, [True])
            self.assertFalse((mine / 'set.asked').exists())

    def test_what_was_heard_by_another_checkout_meanwhile_is_kept(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            out = r.heard_file('fake', r.LENGTHS[0], r.APP_MODEL)
            out.write_text(json.dumps({'0': []}))
            r.keep_heard(out, {'1000': [[0, 'mine']]})
            # Another checkout heard this stretch after this one read the file.
            out.write_text(json.dumps({**json.loads(out.read_text()), '2000': [[0, 'theirs']]}))
            r.keep_heard(out, {'3000': [[0, 'mine again']]})
            self.assertEqual(sorted(json.loads(out.read_text()), key=int), ['0', '1000', '2000', '3000'])

    def test_two_checkouts_hearing_at_once_lose_nothing(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            out = r.heard_file('fake', r.LENGTHS[0], r.APP_MODEL)
            out.write_text('{}')
            with ThreadPoolExecutor(8) as pool:
                list(pool.map(lambda n: r.keep_heard(out, {str(n): []}), range(64)))
            self.assertEqual(len(json.loads(out.read_text())), 64)


class HeardBinaryTest(unittest.TestCase):
    def test_songs_heard_at_once_build_the_hearing_program_once(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'HEARD', Path(directory)):
            program = Path(directory) / 'heard'
            builds = []

            def build(command, check):
                builds.append(command)
                # Long enough for the others to find no program yet.
                threading.Event().wait(0.05)
                program.write_text('')

            with patch.object(r.subprocess, 'run', side_effect=build), ThreadPoolExecutor(4) as pool:
                found = list(pool.map(lambda _: r.heard_binary(), range(4)))
        self.assertEqual([program] * 4, found)
        self.assertEqual(2, len(builds))  # Set up and built, once.


class HearingLoadTest(unittest.TestCase):
    def test_songs_heard_at_once_share_one_limit_on_how_many_stretches_are_heard(self):
        class Samples:
            def __len__(self):
                return 60000 * 16

            def __getitem__(self, key):
                return self

            def tobytes(self):
                return b"fake audio"

        lock, running, peak = threading.Lock(), [0], [0]

        def heard(*_, **__):
            with lock:
                running[0] += 1
                peak[0] = max(peak[0], running[0])
            threading.Event().wait(0.01)
            with lock:
                running[0] -= 1
            return argparse.Namespace(stdout=b'0\t word\n')

        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'as_the_phone_hears', return_value=Samples()), \
                patch.object(r, 'heard_binary', return_value=Path('heard')), \
                patch.object(r, 'model_file', return_value=Path('model')), \
                patch.object(r, 'sung_language', return_value='en'), \
                patch.object(r.subprocess, 'run', side_effect=heard), ThreadPoolExecutor(r.SONGS_HEARD_AT_ONCE) as pool:
            list(pool.map(lambda song: r.windows(song, Path('fake.wav'), 20000), [f'song{n}' for n in range(r.SONGS_HEARD_AT_ONCE)]))
        self.assertLessEqual(peak[0], r.HEARD_AT_ONCE)
        self.assertGreater(peak[0], 1)

    def test_hearing_leaves_most_of_the_machine_for_who_is_using_it(self):
        self.assertLessEqual(r.HEARD_AT_ONCE, 4)


class CheckpointTest(unittest.TestCase):
    def test_interrupted_hearing_keeps_and_resumes_completed_window(self):
        class Samples:
            def __len__(self):
                return 22001 * 16

            def __getitem__(self, key):
                return self

            def tobytes(self):
                return b"fake audio"

        class SerialPool:
            def __init__(self, workers):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def map(self, fn, values):
                return map(fn, values)

        import subprocess
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'as_the_phone_hears', return_value=Samples()), \
                patch.object(r, 'heard_binary', return_value=Path('heard')), \
                patch.object(r, 'model_file', return_value=Path('model')), \
                patch.object(r, 'sung_language', return_value='en'), \
                patch.object(r, 'ThreadPoolExecutor', SerialPool):
            answer = argparse.Namespace(stdout=b'0\t word\n')
            with patch.object(r.subprocess, 'run', side_effect=[answer, subprocess.CalledProcessError(1, 'heard')]):
                with self.assertRaises(subprocess.CalledProcessError):
                    r.windows('fake', Path('fake.wav'), 20000)
            checkpoint = r.heard_file('fake', 20000, r.APP_MODEL)
            self.assertEqual({'0': [[0, ' word']]}, json.loads(checkpoint.read_text()))
            with patch.object(r.subprocess, 'run', return_value=answer) as heard:
                r.windows('fake', Path('fake.wav'), 20000)
                self.assertEqual(2, heard.call_count)
            self.assertEqual({'0', '1000', '2000'}, set(json.loads(checkpoint.read_text())))

    def test_complete_rejects_partial_windows(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            for suffix in ('wav', 'language', 'lyrics.json', 'truth.jsonl'):
                (r.OUT / f'fake.{suffix}').write_text('fake')
            for length in r.LENGTHS:
                r.heard_file('fake', length, r.APP_MODEL).write_text('{}')
            with patch.object(r.subprocess, 'check_output', return_value=b'21.021'):
                self.assertFalse(r.complete('fake'))
                for length in r.LENGTHS:
                    r.heard_file('fake', length, r.APP_MODEL).write_text(json.dumps({str(n): [] for n in range(0, 21020 - length, 1000)}))
                self.assertTrue(r.complete('fake'))

class CacheTest(unittest.TestCase):
    def test_changed_lyrics_rebuild_truth_and_keep_previous_reference(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'fake.wav').write_bytes(b'fake audio')
            (r.OUT / 'fake.lyrics.json').write_text('[[0, "Old words"]]')
            (r.OUT / 'fake.truth.jsonl').write_text('old reference')
            incoming = r.OUT / 'incoming.json'
            incoming.write_text('[[0, "New words"]]')
            with patch.object(r, 'truth') as truth, patch.object(r, 'separated_voice'), patch.object(r, 'windows'):
                r.prep(argparse.Namespace(id='fake', lyrics=str(incoming)))
            truth.assert_called_once()
            self.assertEqual('old reference', (r.OUT / 'fake.truth.jsonl.previous').read_text())
            self.assertFalse((r.OUT / 'fake.truth.jsonl').exists())

    def test_interrupted_audio_download_is_not_published_and_can_resume(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            incoming = r.OUT / 'incoming.json'
            incoming.write_text('[[0, "Fake words"]]')
            def download(command, **kwargs):
                destination = Path(command[command.index('-o') + 1].replace('%(ext)s', 'wav'))
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(b'partial audio')
                raise subprocess.CalledProcessError(1, command)
            with patch.object(r.subprocess, 'run', side_effect=download):
                with self.assertRaises(subprocess.CalledProcessError):
                    r.prep(argparse.Namespace(id='fake', lyrics=str(incoming)))
            self.assertFalse((r.OUT / 'fake.wav').exists())
            def finish(command, **kwargs):
                destination = Path(command[command.index('-o') + 1].replace('%(ext)s', 'wav'))
                self.assertFalse(destination.exists())
                destination.write_bytes(b'complete audio')
            with patch.object(r.subprocess, 'run', side_effect=finish), patch.object(r, 'truth'), \
                    patch.object(r, 'separated_voice'), patch.object(r, 'windows'):
                r.prep(argparse.Namespace(id='fake', lyrics=str(incoming)))
            self.assertEqual(b'complete audio', (r.OUT / 'fake.wav').read_bytes())

    def test_unchanged_lyrics_preserve_truth(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            for suffix, content in [('wav', 'fake audio'), ('lyrics.json', '[[0, "Same words"]]'), ('truth.jsonl', 'saved reference')]:
                (r.OUT / f'fake.{suffix}').write_text(content)
            with patch.object(r, 'truth') as truth, patch.object(r, 'windows'):
                r.prep(argparse.Namespace(id='fake', lyrics=str(r.OUT / 'fake.lyrics.json')))
            truth.assert_not_called()
            self.assertEqual('saved reference', (r.OUT / 'fake.truth.jsonl').read_text())

    def test_complete_model_download_is_published(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            def download(command, **kwargs):
                Path(command[command.index('-o') + 1]).write_bytes(b'complete weights')
            with patch.object(r.subprocess, 'run', side_effect=download):
                result = r.model_file('fake')
            self.assertEqual(b'complete weights', result.read_bytes())
            self.assertFalse(result.with_name(result.name + '.download').exists())

    def test_failed_model_download_never_publishes_partial_weights(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            def download(command, **kwargs):
                Path(command[command.index('-o') + 1] if '-o' in command else command[2]).write_bytes(b'partial weights')
                raise subprocess.CalledProcessError(1, command)
            with patch.object(r.subprocess, 'run', side_effect=download):
                with self.assertRaises(subprocess.CalledProcessError):
                    r.model_file('fake')
            self.assertFalse((r.OUT / 'ggml-fake.bin').exists())

class TableTest(unittest.TestCase):
    def test_overall_includes_worst_dark_and_reference_coverage(self):
        import io
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'prepped', return_value=['fake']), \
                patch.object(r.subprocess, 'run', return_value=argparse.Namespace(returncode=0)):
            (r.OUT / 'fake.from0.shown.json').write_text(json.dumps(dict(
                lines=['One', 'Two', 'Three', 'Unmatched'],
                shown=[dict(line=0, at=10000), dict(line=1, at=11000)])))
            (r.OUT / 'fake.lyrics.json').write_text(json.dumps([[0, 'One'], [1, 'Two'], [2, 'Three'], [3, 'Unmatched']]))
            (r.OUT / 'fake.truth.jsonl').write_text(''.join(json.dumps(dict(line=n, start=start)) + '\n'
                                                       for n, start in enumerate([10.2, 20, 30])))
            output = io.StringIO()
            with redirect_stdout(output):
                r.run_all(argparse.Namespace(gradle='fake-gradle', work=4500, behind=0, heard=''))
            rows = output.getvalue().splitlines()
            self.assertEqual(['all', '3', '1', '1', '1', '1', '-9.0'], rows[2].split()[:7])
            self.assertIn('Reference coverage: 3/4', rows[3])
            self.assertFalse((r.OUT / 'replay.json').exists())
            self.assertFalse((r.OUT / 'replay.songs').exists())

class HeardWhenAskedTest(unittest.TestCase):
    def test_prep_hears_every_20_seconds_and_the_last_stretch(self):
        self.assertEqual(r.seed(list(range(0, 45000, 1000))), [0, 20000, 40000, 44000])

    def test_a_song_with_only_those_is_complete(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            for suffix in ('wav', 'language', 'lyrics.json', 'truth.jsonl'):
                (r.OUT / f'fake.{suffix}').write_text('fake')
            for length in r.LENGTHS:
                r.heard_file('fake', length, r.APP_MODEL).write_text(json.dumps({str(n): [] for n in r.seed(list(range(0, 61020 - length, 1000)))}))
            with patch.object(r.subprocess, 'check_output', return_value=b'61.021'):
                self.assertTrue(r.complete('fake'))

    def asked(self, heard, kept):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), patch.object(r, 'windows') as windows:
            r.heard_file('fake', 20000, r.APP_MODEL).write_text(json.dumps({str(n): [] for n in kept}))
            (r.OUT / 'fake.from0.shown.json').write_text(json.dumps(dict(heard=heard)))
            new = r.hear_asked('fake', ['fake.from0.shown.json'])
        return new, [call.kwargs['wanted'](list(range(0, 41000, 1000))) for call in windows.call_args_list]

    def test_a_stretch_asked_for_is_heard_at_the_nearest_second(self):
        new, wanted = self.asked([dict(seconds=20, **{'from': 3400}), dict(seconds=20, **{'from': 7600})], [0, 40000])
        self.assertEqual((new, wanted), (2, [[3000, 8000]]))

    def test_past_the_last_stretch_or_before_the_first_is_the_nearest_there_is(self):
        self.assertEqual(self.asked([dict(seconds=20, **{'from': 90000}), dict(seconds=20, **{'from': -900})], [0, 40000]), (0, []))

    def test_what_was_heard_already_is_not_heard_again(self):
        self.assertEqual(self.asked([dict(seconds=20, **{'from': 3000})], [0, 3000, 40000]), (0, []))

    def test_a_song_with_no_replay_yet_asks_for_nothing(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), patch.object(r, 'windows') as windows:
            self.assertEqual(r.hear_asked('fake', ['fake.from0.shown.json']), 0)
        windows.assert_not_called()

    def replays(self, run, args, new):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'prepped', return_value=['fake']), patch.object(r, 'hear_asked', side_effect=new) as heard, \
                patch.object(r, 'score', return_value=None), \
                patch.object(r.subprocess, 'run', return_value=argparse.Namespace(returncode=0)) as gradle:
            (r.OUT / 'fake.from0.shown.json').write_text(json.dumps(dict(lines=['One'], shown=[dict(line=0, at=1000)])))
            (r.OUT / 'fake.lyrics.json').write_text(json.dumps([[1000, 'One']]))
            (r.OUT / 'fake.truth.jsonl').write_text(json.dumps(dict(line=0, start=1.0)) + '\n')
            with patch('sys.stdout'):
                run(args)
        return gradle.call_count, heard.call_count

    def test_all_replays_until_nothing_new_is_asked(self):
        args = argparse.Namespace(gradle='fake-gradle', work=4500, behind=0, heard='')
        self.assertEqual(self.replays(r.run_all, args, [2, 1, 0]), (3, 3))

    def test_all_hears_nothing_for_another_set(self):
        args = argparse.Namespace(gradle='fake-gradle', work=4500, behind=0, heard='.other')
        self.assertEqual(self.replays(r.run_all, args, []), (1, 0))

    def run_args(self, **changed):
        return argparse.Namespace(**{**dict(id='fake', start=0, work=4500, behind=0, model=r.APP_MODEL, like_phone=False, behind_again=None, skew=None, shazam_ahead=0, words_length=None, video=False, player_speed=1.0, gradle='fake-gradle'), **changed})

    def test_one_song_replays_until_nothing_new_is_asked(self):
        self.assertEqual(self.replays(r.run, self.run_args(), [1, 0]), (2, 2))

    def test_one_song_on_another_model_hears_nothing(self):
        self.assertEqual(self.replays(r.run, self.run_args(model='base-q5_1'), []), (1, 0))

    def test_a_phone_checks_answers_are_placed_on_the_players_clock(self):
        log = "\n".join((
            "11-04 17:57:54.887 7559 7559 D CrosstuneTiming: said 23891 at 2341106216 playing true, kept 23891 at 2341106216",
            "11-04 17:57:55.088 7559 7559 D CrosstuneTiming: recognized 177000 at 2341106000 match 40 received 2341106417 speed 0.5 length 294000",
            "11-04 17:57:58.048 7559 7559 D CrosstuneTiming: fingerprint 50155 at 2341105307 match 40 app Following(clock=x) lead null guessed null",
            "11-04 17:58:11.108 7559 7559 D CrosstuneTiming: fingerprint 23906 at 2341118482 match 31 received 2341122437 speed 0.997 length null app x",
        ))
        answers = r.phone_answers(log, 294000, 309000)
        zero = 2341106216 - 23891
        self.assertEqual(dict(at=2341106000 - zero, receivedAt=24092, songMs=177000, matchId='40', speed=0.5), answers['initial'])
        self.assertEqual([
            dict(at=2341105307 - zero, receivedAt=27052, songMs=50155, matchId='40', speed=1.0),
            dict(at=2341118482 - zero, receivedAt=40112, songMs=23906, matchId='31', speed=0.997),
        ], answers['events'])
        self.assertEqual((294000, 309000), (answers['wordsLength'], answers['videoLength']))

    def test_an_older_phone_check_starts_from_the_first_line_lit(self):
        log = "\n".join((
            "11-04 17:57:54.887 7559 7559 D CrosstuneTiming: said 23891 at 2341106216 playing true, kept 23891 at 2341106216",
            "11-04 17:57:55.088 7559 7559 D CrosstuneTiming: lit 11 at 2341106423 pos 177755",
            "11-04 17:57:58.048 7559 7559 D CrosstuneTiming: fingerprint 50155 at 2341105307 match 40 app x",
        ))
        answers = r.phone_answers(log, 294000, 309000)
        self.assertEqual(dict(at=24098, receivedAt=24098, songMs=177755, matchId='40', speed=1.0), answers['initial'])

    def test_a_phone_check_without_a_clock_or_an_answer_says_so(self):
        with self.assertRaisesRegex(SystemExit, 'no clock'):
            r.phone_answers("11-04 17:57:55.088 7559 7559 D CrosstuneTiming: lit 11 at 2341106423 pos 177755", 1, 1)
        with self.assertRaisesRegex(SystemExit, 'no answer'):
            r.phone_answers("11-04 17:57:54.887 7559 7559 D CrosstuneTiming: said 1 at 2 playing true, kept 1 at 2", 1, 1)

    def test_recorded_fingerprints_are_given_to_the_app_replay(self):
        with patch.object(r, 'hear_asked', return_value=0), patch.object(r, 'score'), \
                tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            fingerprints = r.OUT / 'fingerprints.json'
            fingerprints.write_text(json.dumps(dict(initial=dict(at=20000, songMs=10000, receivedAt=20000), events=[])))
            def replay(*args, **kwargs):
                asked = json.loads((r.OUT / 'replay.json').read_text())
                self.assertEqual(json.loads(fingerprints.read_text()), asked.get('fingerprints'))
                self.assertEqual(1.05, asked['playerSpeed'])
                return argparse.Namespace(returncode=0)
            with patch.object(r.subprocess, 'run', side_effect=replay):
                r.run(self.run_args(fingerprints=str(fingerprints), player_speed=1.05))

    def test_a_replay_held_to_the_phone_takes_each_while_as_long_as_the_phone_did(self):
        asked = []
        def replay(*_, **__):
            asked.append(json.loads((r.OUT / 'replay.json').read_text()))
            return argparse.Namespace(returncode=0)
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'hear_asked', return_value=0), patch.object(r, 'score', return_value=None), \
                patch.object(r, 'score_phone', return_value=({}, 0)), patch.object(r, 'compare'), patch.object(r.subprocess, 'run', side_effect=replay):
            kept = Path(directory) / 'phone' / 'fake'
            kept.mkdir(parents=True)
            (kept / 'log.txt').write_text('said 566 at 66 playing true')
            r.heard_file('fake', r.LENGTHS[0], r.APP_MODEL).write_text('{}')
            # 20 s heard each time, begun 3.8 s, 27.1 s and 11.3 s after the last one ended.
            for start in (10000, 33800, 80900, 112200):
                with open(kept / f'heard-{start}.pcm', 'wb') as sound:
                    sound.truncate(640000)
            r.run(self.run_args(id='fake', like_phone=True))
        self.assertEqual(asked[0]['works'], [3800, 27100, 11300])
        self.assertEqual(asked[0]['work'], 3800)
        self.assertEqual(asked[0]['whiles'], [10500, 34300, 81400, 112700])

    def test_a_second_play_asks_too(self):
        with patch.object(r, 'hear_asked', return_value=0) as heard, patch.object(r, 'score', return_value=None), \
                tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r.subprocess, 'run', return_value=argparse.Namespace(returncode=0)):
            r.run(self.run_args(behind_again=500, skew=None, shazam_ahead=0, words_length=None, video=False))
        heard.assert_called_once_with('fake', ['fake.from0.first.shown.json', 'fake.from0.shown.json'])


class ReplayAllTest(unittest.TestCase):
    def test_it_replays_the_set_of_what_was_heard_it_is_asked_for(self):
        asked = []
        def replay(*_, **__):
            asked.append(json.loads((r.OUT / 'replay.json').read_text()))
            return argparse.Namespace(returncode=0)
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'prepped', return_value=['fake']), patch.object(r.subprocess, 'run', side_effect=replay):
            (r.OUT / 'fake.from0.shown.json').write_text(json.dumps(dict(lines=['One'], shown=[dict(line=0, at=1000)])))
            (r.OUT / 'fake.lyrics.json').write_text(json.dumps([[0, 'One']]))
            (r.OUT / 'fake.truth.jsonl').write_text(json.dumps(dict(line=0, start=1.0)) + '\n')
            with patch('sys.stdout'):
                r.run_all(argparse.Namespace(gradle='fake-gradle', work=4500, behind=0, heard='.thin5'))
        self.assertEqual(asked[0]['songs'][0]['heard'], '.thin5')


class WordsHeardTest(unittest.TestCase):
    def test_a_gpu_python_hears_the_words_with_the_same_model(self):
        with tempfile.TemporaryDirectory() as temp:
            gpu = Path(temp) / 'python'
            asked = Path(temp) / 'asked'
            gpu.write_text(f"""#!/bin/sh
echo "$@" > {asked}
echo '[[["Every", 8.9], ["time", 9.2]], [["Every", 8.9]]]'
""")
            gpu.chmod(0o755)
            with patch.dict(os.environ, {'WHISPER_PYTHON': str(gpu)}), patch.object(r, 'truth_model') as cpu:
                heard = r.words_heard(Path('vocals.wav'), 'en')
            cpu.assert_not_called()
            self.assertEqual(heard, [[('every', 8.9), ('time', 9.2)], [('every', 8.9)]])
            said = asked.read_text()
            self.assertIn('whisper_gpu.py vocals.wav en', said)

    def test_a_failure_on_the_gpu_stops_rather_than_passing_for_no_words(self):
        with patch.dict(os.environ, {'WHISPER_PYTHON': '/bin/false'}), patch.object(r, 'truth_model'):
            with self.assertRaises(subprocess.CalledProcessError):
                r.words_heard(Path('vocals.wav'), 'en')

    def test_without_one_it_stays_on_the_cpu(self):
        word = SimpleNamespace(word=' Every', start=8.9)
        model = Mock()
        model.transcribe.return_value = ([SimpleNamespace(words=[word])], None)
        env = {key: value for key, value in os.environ.items() if key != 'WHISPER_PYTHON'}
        with patch.dict(os.environ, env, clear=True), patch.object(r, 'truth_model', return_value=model), patch.object(r, 'audio'):
            self.assertEqual(r.words_heard(Path('vocals.wav'), 'en'), [[('every', 8.9)]])


    def test_each_letter_of_a_word_in_unspaced_writing_is_sung_after_the_one_before(self):
        # Whisper hears a line's last word and the next line's first as one: the next line starts two letters in.
        model = Mock()
        model.transcribe.return_value = ([SimpleNamespace(words=[SimpleNamespace(word='歩く朝の', start=10.0), SimpleNamespace(word=' Every', start=11.0)])], None)
        env = {key: value for key, value in os.environ.items() if key != 'WHISPER_PYTHON'}
        with patch.dict(os.environ, env, clear=True), patch.object(r, 'truth_model', return_value=model), patch.object(r, 'audio'):
            heard = r.words_heard(Path('vocals.wav'), 'ja')
        self.assertEqual(heard, [[('歩', 10.0), ('く', 10.28), ('朝', 10.56), ('の', 10.84), ('every', 11.0)]])
        self.assertEqual(r.line_starts(heard[0], ['夜の海を歩く', '朝の光']), {1: 10.56})


class SungLanguageTest(unittest.TestCase):
    """What's heard goes by the audio, unless the lyrics can't be in that language: Dynamite sounded Korean."""
    def told(self, lyrics, probabilities):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)), \
                patch.object(r, 'separated_voice', return_value=Path('vocals.wav')), patch.object(r, 'audio', return_value=[]), \
                patch.object(r, 'truth_model') as model:
            (Path(folder) / 'song.lyrics.json').write_text(json.dumps([[0, line] for line in lyrics]))
            model.return_value.detect_language.return_value = (probabilities[0][0], probabilities[0][1], probabilities)
            first = r.sung_language('song')
            model.return_value.detect_language.side_effect = AssertionError('kept, so not told again')
            self.assertEqual(first, r.sung_language('song'))
            return first

    def test_lyrics_with_none_of_that_writing_rule_a_language_out(self):
        self.assertEqual('en', self.told(["'Cause I'm in the stars tonight"], [('ko', 0.6), ('ja', 0.3), ('en', 0.1)]))

    def test_a_little_of_that_writing_is_enough(self):
        self.assertEqual('ko', self.told(['Aha, listen boy', '너무너무 멋져'], [('ko', 0.6), ('en', 0.4)]))

    def test_kanji_alone_can_be_japanese(self):
        self.assertEqual('ja', self.told(['移り行く街'], [('ja', 0.9), ('zh', 0.1)]))

    def test_a_language_in_latin_letters_stays_what_it_sounds_like(self):
        self.assertEqual('es', self.told(['No puedo creer'], [('es', 0.9), ('en', 0.1)]))

    def test_no_language_fits_goes_by_how_it_sounds(self):
        self.assertEqual('ko', self.told(['移り行く'], [('ko', 0.6), ('ru', 0.4)]))


class EveryWritingTest(unittest.TestCase):
    """Truth was blind to lyrics in anything but Latin letters: Japanese songs scored no line at all."""
    def test_latin_words_stay_one_piece_without_accents_or_punctuation(self):
        self.assertEqual(["don't"], r.pieces('Don’t,'))
        self.assertEqual(['corazon'], r.pieces('corazón'))
        self.assertEqual(['bababa'], r.pieces('ba-ba-ba'))
        self.assertEqual([], r.pieces('...'))

    def test_writing_without_spaces_goes_letter_by_letter(self):
        self.assertEqual(['移', 'り', '行', 'く'], r.pieces('移り行く'))
        self.assertEqual(['ガ', 'ラ', 'ス'], r.pieces('ｶﾞﾗｽ'))
        self.assertEqual(['너', '무'], r.pieces('너무,'))

    def test_other_spaced_writing_stays_whole_words(self):
        self.assertEqual(['любовь'], r.pieces('Любовь!'))

    def test_lines_in_japanese_are_timed(self):
        heard = [('移', 5.0), ('り', 5.0), ('行', 5.5), ('く', 5.5), ('街', 6.0), ('心', 9.0), ('視', 9.5)]
        self.assertEqual({0: 5.0, 1: 9.0}, r.line_starts(heard, ['移り行く街', '心視']))

    def test_hearing_gives_each_letter_a_time_a_letter_after_the_one_before(self):
        with patch.dict(os.environ, {'WHISPER_PYTHON': 'gpu'}), \
                patch.object(r.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([[['移り', 5.0], ['行く', 6.0]]]))):
            self.assertEqual([[('移', 5.0), ('り', 5.28), ('行', 6.0), ('く', 6.28)]], r.words_heard(Path('voice.wav'), 'ja'))


class AgreedTest(unittest.TestCase):
    def test_a_line_timed_apart_by_two_hearings_is_left_out(self):
        # The Police's chorus: one hearing lost it at 120 s and matched the line to the next one, at 161 s.
        self.assertEqual(r.agreed([{25: 120.57, 27: 126.24}, {25: 161.78, 27: 126.3}]), {27: 126.24})

    def test_a_line_only_one_hearing_found_is_left_out(self):
        self.assertEqual(r.agreed([{1: 10.0, 2: 20.0}, {1: 10.0}]), {1: 10.0})

    def test_times_within_what_counts_as_on_time_are_the_same_time(self):
        self.assertEqual(r.agreed([{1: 10.0}, {1: 10.0 + r.ON_TIME_S}]), {1: 10.0})

    def test_one_hearing_has_nothing_to_disagree_with(self):
        self.assertEqual(r.agreed([{1: 10.0, 2: 20.0}]), {1: 10.0, 2: 20.0})


class TruthTest(unittest.TestCase):
    def test_lines_come_from_what_every_hearing_agrees_on(self):
        lines = ['every little thing', 'she does is magic']
        chorus = [('every', 10.0), ('little', 10.5), ('thing', 11.0), ('she', 12.0), ('does', 12.5), ('is', 13.0), ('magic', 13.5)]
        lost = [('every', 50.0), ('little', 50.5), ('thing', 51.0), ('she', 12.0), ('does', 12.5), ('is', 13.0), ('magic', 13.5)]
        with patch.object(r, 'separated_voice', return_value=Path('vocals.wav')), patch.object(r, 'sung_language', return_value='en'), \
                patch.object(r, 'words_heard', return_value=[chorus, lost]), patch.object(r, 'voiced', side_effect=lambda _, starts: starts), \
                patch.object(r, 'write_truth') as wrote:
            r.truth('song', Path('song.wav'), lines)
        wrote.assert_called_once_with('song', lines, {1: 12.0})


class WordsShownTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.object(r, 'OUT', Path(directory.name))
        patcher.start()
        self.addCleanup(patcher.stop)
        (r.OUT / 'song.lyrics.json').write_text(json.dumps([[1000, 'One two, three'], [2000, ''], [3000, 'Four five'], [4000, 'Six seven eight'], [5000, 'Nine ten']]))
        (r.OUT / 'song.truth.jsonl').write_text(''.join(json.dumps(dict(line=n, text='', start=at)) + '\n' for n, at in ((0, 1.0), (1, 3.1), (2, 4.2), (3, 5.3))))

    def test_truth_follows_a_lines_words_not_its_number(self):
        # Other words for the song, with a line more up front, broken elsewhere and spelt otherwise.
        lines = ['New line here', 'One two three', 'Four, five', '', 'Six seven', 'eight', 'Nine ten']
        self.assertEqual({1: 1.0, 2: 3.1, 3: 4.2, 5: 5.3}, r.sung_lines('song', lines))

    def test_lines_in_any_writing_line_up(self):
        ours = ['\u3042\u306a\u305f\u306e\u80f8\u306e\u4e2d', '\u8857\u306e\u706f\u304c\u6d88\u3048\u308b\u9803']
        self.assertEqual({0: 1, 1: 2}, r.lined_up(ours, ['La la', *ours]))

    def test_the_same_words_keep_their_truth(self):
        self.assertEqual({0: 1.0, 1: 3.1, 2: 4.2, 3: 5.3}, r.sung_lines('song', ['One two, three', '', 'Four five', 'Six seven eight', 'Nine ten']))

    def test_words_with_no_lines_are_all_dark(self):
        self.assertEqual({0: 1.0, 1: 3.1, 2: 4.2, 3: 5.3}, r.sung_lines('song', []))

    def test_a_replay_that_found_no_words_scores_as_all_dark(self):
        (r.OUT / 'song.from0.shown.json').write_text(json.dumps(dict(lines=[], shown=[], placed=[dict(at=0, times=[])], known=[], heard=[])))
        with contextlib.redirect_stdout(io.StringIO()) as said:
            self.assertEqual({}, r.score(argparse.Namespace(id='song', start=0, placed=False)))
        self.assertIn('never shown 4', said.getvalue())

    def test_words_kept_are_this_songs_though_the_next_songs_came_after(self):
        kept = r.OUT / 'kept'
        kept.mkdir()
        (kept / 'lyrics.json').write_text(json.dumps([
            ['this', json.dumps(dict(lines=[[1000, 'One two three'], [3000, 'Four five']]))],
            ['next', json.dumps(dict(lines=[[1000, 'Another song altogether']]))],
        ]))
        self.assertEqual('One two three', r.found_words('song', kept)['lines'][0][1])


class FoundWordsTest(unittest.TestCase):
    def found(self, *kept):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'song.lyrics.json').write_text(json.dumps([[0, 'One two three'], [1, 'Four five six']]))
            (r.OUT / 'lyrics.json').write_text(json.dumps([['key', json.dumps(words)] for words in kept]))
            return r.found_words('song', r.OUT)

    def test_the_words_it_settled_on_over_those_it_first_found(self):
        first = dict(lines=[[0, 'One two three'], [1, 'Four five six']], provisional=True, source='first')
        settled = dict(lines=[[0, 'One two three Four five six']], source='settled')
        self.assertEqual('settled', self.found(first, settled)['source'])

    def test_the_last_of_those_still_to_settle(self):
        first = dict(lines=[[0, 'One two three']], provisional=True, source='first')
        last = dict(lines=[[0, 'One two three']], provisional=True, source='last')
        self.assertEqual('last', self.found(first, last)['source'])

    def test_not_the_next_songs(self):
        next_song = dict(lines=[[0, 'Seven eight nine']], source='next')
        ours = dict(lines=[[0, 'One two three']], provisional=True, source='ours')
        self.assertEqual('ours', self.found(ours, next_song)['source'])


class LearntTest(unittest.TestCase):
    def test_how_far_a_video_is_ahead_is_forgotten_too(self):
        self.assertIn('video_timing', r.LEARNT)


class AdbTest(unittest.TestCase):
    def test_goes_to_the_phone_named_and_no_other(self):
        with patch.dict(r.os.environ, {'ANDROID_SERIAL': 'phone', 'ADB': 'adb-tool'}), patch.object(r.subprocess, 'run') as run:
            r.adb('logcat', '-c')
        self.assertEqual(['adb-tool', '-s', 'phone', 'logcat', '-c'], run.call_args.args[0])

    def test_with_no_phone_named_nothing_runs(self):
        for serial in ({}, {'ANDROID_SERIAL': ''}):
            with patch.dict(r.os.environ, serial, clear=True), patch.object(r.subprocess, 'run') as run:
                with self.assertRaises(SystemExit):
                    r.adb('logcat', '-c')
            run.assert_not_called()


class PhoneTest(unittest.TestCase):
    def test_line_times_use_the_player_even_when_the_following_clock_jumps(self):
        log = ('said 24000 at 100000 playing true, kept 24000 at 100000\n'
               'lit 0 at 116000 pos 30000\n'
               'lit 6 at 142000 pos 93000\n')
        self.assertEqual([(0, 116000, 40000), (6, 142000, 66000)], r.phone_lines(log))

    def test_line_times_without_player_log_keep_the_old_clock(self):
        self.assertEqual([(0, 100, 1000)], r.phone_lines('lit 0 at 100 pos 1000\n'))

    def test_phone_waits_for_the_requested_song_before_opening_lyrics(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'shell', return_value=''), patch.object(r, 'adb'), patch.object(r, 'allow_listening'), \
                patch.object(r.time, 'sleep'), patch.object(r, 'tap', return_value=False), \
                patch.object(r, 'playing', side_effect=[('Previous song', 100), ('Song requested', 200)]) as playing:
            (r.OUT / 'set.json').write_text(json.dumps([dict(id='fake', title='Song requested')]))
            with self.assertRaisesRegex(SystemExit, "Couldn't open"):
                r.watch(argparse.Namespace(id='fake', app='player', again=False, seconds=150, uri=None))
            self.assertEqual(2, playing.call_count)

    def test_learnt_timing_is_forgotten_only_where_the_app_has_settings(self):
        for prefs, edits in (('', 0), ('./shared_prefs/crosstune_preferences.xml\n', len(r.LEARNT))):
            said = []

            def phone(*command):
                said.append(command)
                return prefs if 'find' in command and '-path' in command else ''
            with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                    patch.object(r, 'shell', side_effect=phone), patch.object(r, 'adb'), patch.object(r.time, 'sleep'), patch.object(r, 'allow_listening'), \
                    patch.object(r, 'playing', return_value=('Song', 100)), patch.object(r, 'tap', return_value=False):
                with self.assertRaisesRegex(SystemExit, "Couldn't open"):
                    r.watch(argparse.Namespace(id='fake', app='player', again=False, seconds=150, uri=None))
            self.assertEqual(edits, sum('sed' in command for command in said))

    def test_the_app_gets_notification_access_back_before_it_plays(self):
        listener = f'{r.DEV_APP}/com.astrovm.crosstune.NowPlayingListener'
        # Allowed already, by the phone's own say once allowed, or never.
        for allowed, ends, asked in (([listener], "Couldn't open", False), (['', listener], "Couldn't open", True), ([''], 'no notification access', True)):
            said = []
            answers = iter(allowed)

            def phone(*command):
                said.append(command)
                return 'other/Listener:' + next(answers, allowed[-1]) if command[:2] == ('settings', 'get') else ''
            with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                    patch.object(r, 'shell', side_effect=phone), patch.object(r, 'adb'), patch.object(r.time, 'sleep'), \
                    patch.object(r, 'playing', return_value=('Song', 100)), patch.object(r, 'tap', return_value=False):
                with self.assertRaisesRegex(SystemExit, ends):
                    r.watch(argparse.Namespace(id='fake', app='player', again=False, seconds=150, uri=None))
            self.assertEqual(asked, ('cmd', 'notification', 'allow_listener', listener) in said)
            self.assertEqual(ends == 'no notification access', not any(command[:2] == ('am', 'start') for command in said))
            # Allowing it starts the app, so it comes before the app's stopped and its learning forgotten.
            if ends != 'no notification access':
                checked = max(i for i, command in enumerate(said) if command[:2] == ('settings', 'get'))
                self.assertLess(checked, said.index(('am', 'force-stop', r.DEV_APP)))

    def test_a_screen_nothing_can_read_says_so_instead_of_blaming_the_lock_screen(self):
        # Only one thing at a time may read the screen, and a T3 device session may be it: uiautomator then dies with exit 137.
        with patch.object(r, 'shell', side_effect=subprocess.CalledProcessError(137, 'uiautomator')), patch.object(r.time, 'sleep'):
            for look in (lambda: r.tap('Words'), lambda: r.shows('Song')):
                with self.assertRaisesRegex(SystemExit, 'holds the screen'):
                    look()

    def test_a_screen_without_it_is_only_not_there(self):
        with patch.object(r, 'shell', return_value='<node text="Other" />'), patch.object(r.time, 'sleep'):
            self.assertFalse(r.tap('Words', tries=2))
            self.assertFalse(r.shows('Song', tries=2))

    def test_a_title_keeps_its_commas(self):
        session = 'package=app\nstate=PlaybackState {state=PLAYING(3), position=2041, buffered position=0, speed=1.0, updated=5000, actions=1}\nmetadata: size=9, description=Police, The   Magic Audio Flac, uploader, uploader\n'
        with patch.object(r, 'shell', return_value=session):
            self.assertEqual(('Police, The   Magic Audio Flac', 5000 - 2041), r.playing('app'))

    def test_the_app_may_name_a_song_shorter_than_the_music_app(self):
        self.assertTrue(r.names('<node text="Magic Is Every Little Thing" />', 'Police, The   Magic Is Every Little Thing Audio Flac'))
        self.assertTrue(r.names('<node text="Jump" />', 'Jump'))

    def test_a_word_or_the_history_on_the_main_screen_is_not_the_song(self):
        self.assertFalse(r.names('<node text="Magic" />', 'Police, The   Magic Is Every Little Thing Audio Flac'))
        self.assertFalse(r.names('<node text="Link or song" /><node text="Jump" />', 'Jump'))

    def test_a_line_sung_after_the_check_stopped_isnt_dark(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), patch.object(r, 'audio', return_value=MagicMock()), \
                patch.object(r, 'behind', return_value=0.0), patch('builtins.print') as said:
            (r.OUT / 'song.wav').write_text('')
            (r.OUT / 'song.lyrics.json').write_text(json.dumps([[1000, 'One'], [90000, 'Two']]))
            (r.OUT / 'song.truth.jsonl').write_text(json.dumps(dict(line=0, start=1.0)) + '\n' + json.dumps(dict(line=1, start=90.0)) + '\n')
            kept = r.OUT / 'kept'
            kept.mkdir()
            (kept / 'log.txt').write_text('lit 0 at 100 pos 1000\n')
            r.score_phone('song', kept)
        self.assertIn('never lit 0', said.call_args_list[-1].args[0])

    def test_the_app_keeping_nothing_it_heard_leaves_its_clock_as_it_said(self):
        # Words timed word by word don't need hearing, so a check can end with nothing kept. Plastic Love did.
        with tempfile.TemporaryDirectory() as directory, patch('builtins.print') as said:
            self.assertEqual(0, r.behind(MagicMock(), Path(directory), [(0, 100, 1000)]))
        self.assertIn('heard nothing', said.call_args_list[-1].args[0])

    def test_line_times_go_at_the_players_rate(self):
        log = ('said 24000 at 100000 playing true, kept 24000 at 100000\n'
               'lit 0 at 116000 pos 30000\n'
               'fingerprint 40000 at 110000 match 1 received 120000 speed 1.05 length null app PlaybackClock(positionMs=24000, atMs=100000, speed=1.05, playing=true) video false\n'
               'lit 6 at 200000 pos 93000\n')
        self.assertEqual([(0, 116000, 40800), (6, 200000, 129000)], r.phone_lines(log))

    def test_how_fast_the_words_go_is_by_the_lines_lit_once_settled(self):
        # On main, the words start at the player's rate and go faster once the third hearing is in; a jump isn't a pace.
        log = ('lit 0 at 0 pos 0\nlit 1 at 10000 pos 10500\n'
               'lit 2 at 20000 pos 21000\nlit 3 at 30000 pos 32025\nlit 4 at 40000 pos 43050\n'
               'lit 5 at 50000 pos 60000\nlit 6 at 60000 pos 71025\nlit 7 at 70000 pos 82050\n')
        self.assertAlmostEqual(1.1025, r.lit_pace(log), places=3)

    def test_too_few_lines_lit_dont_say_how_fast_the_words_go(self):
        self.assertIsNone(r.lit_pace('lit 0 at 0 pos 0\n'))

    def test_the_player_rate_is_the_last_the_app_saw(self):
        self.assertEqual(1.05, r.player_rate('app PlaybackClock(positionMs=1, atMs=2, speed=1.0, playing=true)\napp PlaybackClock(positionMs=1, atMs=2, speed=1.05, playing=true)\n'))
        self.assertEqual(1.0, r.player_rate('app null\n'))

    def test_a_file_on_the_phone_opens_instead_of_the_songs_link(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'shell', return_value='') as shell, patch.object(r, 'adb'), patch.object(r, 'allow_listening'), \
                patch.object(r.time, 'sleep'), patch.object(r, 'tap', return_value=False), \
                patch.object(r, 'playing', return_value=('Song', 100)):
            with self.assertRaisesRegex(SystemExit, "Couldn't open"):
                r.watch(argparse.Namespace(id='fake', app='player', again=False, seconds=150, uri='file:///sdcard/Music/song.m4a'))
        self.assertIn(('am', 'start', '-a', 'android.intent.action.VIEW', '-d', 'file:///sdcard/Music/song.m4a', '-t', 'audio/*', '-p', 'player'), [c.args for c in shell.call_args_list])

    def test_no_line_with_truth_lit_still_scores(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), patch.object(r, 'audio', return_value=MagicMock()), \
                patch.object(r, 'behind', return_value=0.0), patch('builtins.print') as said:
            (r.OUT / 'song.wav').write_text('')
            (r.OUT / 'song.lyrics.json').write_text(json.dumps([[1000, 'One'], [2000, 'Two']]))
            (r.OUT / 'song.truth.jsonl').write_text(json.dumps(dict(line=1, start=2.0)) + '\n')
            kept = r.OUT / 'kept'
            kept.mkdir()
            (kept / 'log.txt').write_text('lit 0 at 100 pos 1000\nlit -1 at 200 pos 3000\n')
            r.score_phone('song', kept)
        self.assertIn('never lit 1', said.call_args_list[-1].args[0])


class WhereTheWhileWasHeardTest(unittest.TestCase):
    """What's made of how well a while fits, on what CI's `site` job has: finding how well takes numpy, which it hasn't, so it's stood in for here."""
    def samples(self, seconds=60):
        rng = random.Random(1)
        return array('h', (rng.randrange(-30000, 30000) for _ in range(16000 * seconds)))

    def fits_only_where_its_samples_are(self, near, piece):
        rng = random.Random(piece[0])
        fits = [rng.random() for _ in range(len(near) - len(piece) + 1)]
        for at in range(len(fits)):
            if near[at] == piece[0] and near[at:at + 8] == piece[:8]:
                fits[at] = 2.0
                break
        return fits

    def behind_with(self, song, heard, lit, rate=1.0, fits=None):
        with tempfile.TemporaryDirectory() as directory, patch('builtins.print') as said, \
                patch.object(r, 'fits_along', fits or self.fits_only_where_its_samples_are):
            (Path(directory) / 'heard-5000.pcm').write_bytes(heard.tobytes())
            return r.behind(song, Path(directory), lit, rate), [call.args[0] for call in said.call_args_list]

    def stretched(self, song):
        # As a player keeping the pitch does it: bits of the recording as they are, and every so often one left out.
        return array('h', (sample for at in range(16000 * 10, 16000 * 18, 1680) for sample in song[at:at + 1600]))

    def test_a_while_that_fits_two_places_doesnt_say_where_the_clock_is(self):
        # A riff played twice: heard in it, the match is as good far from the app's clock as near it. Police, run 3: "54 s behind".
        fits = [0.0] * (16000 * 60)
        fits[16000 * 10], fits[16000 * 40] = 1.0, 0.93
        lag, said = self.behind_with(array('h'), array('h', [1]), [(0, 5000, 10000)], fits=lambda *_: fits)
        self.assertEqual(0, lag)
        self.assertIn('fits more than one place', said[0])

    def test_a_while_that_fits_one_place_says_how_far_behind_the_clock_is(self):
        # True fits measured up to 0.85 of the best, near it: those aren't another place.
        fits = [0.0] * (16000 * 60)
        fits[16000 * 10], fits[16000 * 10 + 8000], fits[16000 * 40] = 1.0, 0.85, 0.7
        lag, _ = self.behind_with(array('h'), array('h', [1]), [(0, 5000, 9500)], fits=lambda *_: fits)
        self.assertEqual(500, lag)

    def test_a_while_heard_at_another_rate_says_how_far_behind_the_clock_is(self):
        song = self.samples()
        lag, _ = self.behind_with(song, self.stretched(song), [(0, 5000, 9500)], 1.05)
        self.assertAlmostEqual(500, lag, delta=15)

    def test_a_while_heard_at_another_rate_goes_by_the_rate_since_the_line_lit(self):
        song = self.samples()
        # Lit 2 s before the while started: the clock says 8000 + 2000 x 1.05 = 10100, so it's 100 ms ahead.
        lag, _ = self.behind_with(song, self.stretched(song), [(0, 3000, 8000)], 1.05)
        self.assertAlmostEqual(-100, lag, delta=15)

    def test_a_while_at_another_rate_that_fits_nowhere_doesnt_say(self):
        song = self.samples()
        rng = random.Random(2)
        lag, said = self.behind_with(song, array('h', (rng.randrange(-30000, 30000) for _ in range(16000 * 8))), [(0, 5000, 9500)], 1.05)
        self.assertEqual(0, lag)
        self.assertIn("doesn't say", said[0])

    def test_silence_at_another_rate_says_nothing(self):
        song = self.samples(20)
        self.assertIsNone(r.found_at_rate(song, array('h', [0] * 16000), 5000, 1.05))


@unittest.skipUnless(importlib.util.find_spec('numpy'), "CI's site job has no numpy; the replay's own Python has it")
class FitsAlongTest(unittest.TestCase):
    def test_a_while_fits_where_it_was_taken_from_even_next_to_something_louder(self):
        import numpy as np
        song = np.random.default_rng(1).normal(0, 3000, 16000 * 20).astype(np.float32)
        song[16000 * 15:] *= 5
        fits = r.fits_along(song, song[16000 * 5:16000 * 8])
        self.assertEqual(16000 * 5, r.found_in_one_place(fits))

    def test_bits_of_a_while_played_faster_still_find_it(self):
        import numpy as np
        song = np.random.default_rng(1).normal(0, 3000, 16000 * 60).astype(np.float32)
        heard = array('h', np.concatenate([song[at:at + 1600] for at in range(16000 * 10, 16000 * 18, 1680)]).astype(np.int16).tobytes())
        self.assertAlmostEqual(10000, r.found_at_rate(song, heard, 9500, 1.05), delta=15)


class PhoneVolumeTest(unittest.TestCase):
    def volumes_with_the_phone_at(self, level):
        """The phone's media volume while the song plays, and after."""
        volume = [level]
        heard = []
        def shell(*parts):
            if '--set' in parts:
                volume[0] = int(parts[parts.index('--set') + 1])
            return f'[V] volume is {volume[0]} in range [0..15]'
        with patch.object(r, 'shell', side_effect=shell), patch.object(r, 'watch', side_effect=lambda args: heard.append(volume[0])):
            r.phone(argparse.Namespace())
        return heard[0], volume[0]

    def test_plays_at_one_from_silence_and_puts_it_back(self):
        self.assertEqual(self.volumes_with_the_phone_at(0), (1, 0))

    def test_plays_at_one_from_louder_and_puts_it_back(self):
        self.assertEqual(self.volumes_with_the_phone_at(9), (1, 9))


if __name__ == '__main__':
    unittest.main()
