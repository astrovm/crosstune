import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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
                r.run_all(argparse.Namespace(gradle='fake-gradle', work=4500, behind=0))
            rows = output.getvalue().splitlines()
            self.assertEqual(['all', '3', '1', '1', '1', '1', '-9.0'], rows[2].split()[:7])
            self.assertIn('Reference coverage: 3/4', rows[3])
            self.assertFalse((r.OUT / 'replay.json').exists())
            self.assertFalse((r.OUT / 'replay.songs').exists())

if __name__ == '__main__':
    unittest.main()
