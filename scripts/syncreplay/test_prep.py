import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('replay', Path(__file__).with_name('syncreplay.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

class ResumeTest(unittest.TestCase):
    def test_truth_without_hearing_is_resumed(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'set.json').write_text(json.dumps([dict(id='fake', lines=1, title='Song', artist='Artist')]))
            (r.OUT / 'fake.truth.jsonl').write_text('{}\n')
            with patch.object(r, 'prep') as prep, patch.object(r.subprocess, 'run', return_value=argparse.Namespace(returncode=0)):
                r.find_set(argparse.Namespace(cached=True, find_only=False, gradle="fake-gradle"))
            prep.assert_called_once()

    def test_hearing_alone_does_not_make_song_prepped(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)):
            (r.OUT / 'fake.truth.jsonl').write_text('{}\n')
            (r.OUT / 'fake.heard20.json').write_text('{}')
            self.assertEqual([], r.prepped())

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

class Sound:
    """Nine seconds of sound, as long as it needs to be for one stretch of 8."""
    def __len__(self):
        return 16 * 9000

    def __getitem__(self, _):
        return SimpleNamespace(tobytes=lambda: b'')


class HeardOnTheGpuTest(unittest.TestCase):
    def built(self, gpu):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'HEARD', Path(directory) / 'heard'), patch.object(r.subprocess, 'run') as run:
            return r.heard_binary(gpu).parent.name, run.call_args_list[0].args[0]

    def test_the_gpu_has_its_own_build_with_vulkan(self):
        folder, cmake = self.built(True)
        self.assertEqual(folder, 'heard-gpu')
        self.assertIn('-DGGML_VULKAN=ON', cmake)

    def test_the_cpu_build_has_no_vulkan(self):
        folder, cmake = self.built(False)
        self.assertEqual(folder, 'heard')
        self.assertNotIn('-DGGML_VULKAN=ON', cmake)

    def test_with_it_on_the_gpu_works_alongside_the_cpu(self):
        with patch.dict(os.environ, {'HEARD_GPU': '1'}):
            self.assertEqual(r.hearers(), [True] * r.HEARD_ON_THE_GPU_AT_ONCE + [False] * r.HEARD_AT_ONCE)

    def test_without_it_only_the_cpu_hears(self):
        env = {key: value for key, value in os.environ.items() if key != 'HEARD_GPU'}
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(r.hearers(), [False] * r.HEARD_AT_ONCE)

    def test_the_cpu_gives_way_and_the_gpu_does_not(self):
        heard = []
        def answer(command, **kwargs):
            heard.append(command[:3])
            return argparse.Namespace(stdout=b'0\tword\n')
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'hearers', return_value=[False]), patch.object(r, 'as_the_phone_hears', return_value=Sound()), \
                patch.object(r, 'heard_binary', side_effect=lambda gpu: Path('gpu' if gpu else 'cpu')), patch.object(r, 'model_file', return_value=Path('tiny')), \
                patch.object(r, 'sung_language', return_value='en'), patch.object(r.subprocess, 'run', side_effect=answer):
            r.windows('song', Path('song.wav'), 8000)
        self.assertEqual(heard, [['nice', '-n', '10']])

    def test_only_a_hearer_on_the_gpu_is_told_to_use_it(self):
        heard = []
        def answer(command, **kwargs):
            heard.append((command[0], kwargs['env'].get('HEARD_GPU')))
            return argparse.Namespace(stdout=b'0\tword\n')
        env = {key: value for key, value in os.environ.items() if key != 'HEARD_GPU'}
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), patch.dict(os.environ, env, clear=True), \
                patch.object(r, 'hearers', return_value=[True]), patch.object(r, 'as_the_phone_hears', return_value=Sound()), \
                patch.object(r, 'heard_binary', side_effect=lambda gpu: Path('gpu' if gpu else 'cpu')), patch.object(r, 'model_file', return_value=Path('tiny')), \
                patch.object(r, 'sung_language', return_value='en'), patch.object(r.subprocess, 'run', side_effect=answer):
            r.windows('song', Path('song.wav'), 8000)
        self.assertEqual(heard, [('gpu', '1')])


class ReplayAllTest(unittest.TestCase):
    def test_it_replays_the_set_of_what_was_heard_it_is_asked_for(self):
        asked = []
        def replay(*_, **__):
            asked.append(json.loads((r.OUT / 'replay.json').read_text()))
            return argparse.Namespace(returncode=0)
        with tempfile.TemporaryDirectory() as directory, patch.object(r, 'OUT', Path(directory)), \
                patch.object(r, 'prepped', return_value=['fake']), patch.object(r.subprocess, 'run', side_effect=replay):
            (r.OUT / 'fake.from0.shown.json').write_text(json.dumps(dict(lines=['One'], shown=[dict(line=0, at=1000)])))
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


if __name__ == '__main__':
    unittest.main()
