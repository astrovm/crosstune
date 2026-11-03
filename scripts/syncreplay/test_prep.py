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
            with patch.object(r, 'prep') as prep:
                r.find_set(argparse.Namespace(cached=True, find_only=False))
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

if __name__ == '__main__':
    unittest.main()
