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

if __name__ == '__main__':
    unittest.main()
