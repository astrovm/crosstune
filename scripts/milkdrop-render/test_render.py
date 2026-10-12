"""Regression tests for renderer cleanup with real subprocesses and pipes."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

spec = importlib.util.spec_from_file_location('milkdrop_render', Path(__file__).with_name('render.py'))
render = importlib.util.module_from_spec(spec)
spec.loader.exec_module(render)


class EncoderCleanupTest(unittest.TestCase):
    def test_success_reaps_renderer(self):
        child = subprocess.Popen([sys.executable, '-c', "import sys; sys.stdout.buffer.write(b'frames')"], stdout=subprocess.PIPE)
        render.encode_frames(child, [sys.executable, '-c', "import sys; assert sys.stdin.buffer.read() == b'frames'"])
        self.assertEqual(child.returncode, 0)
        self.assertTrue(child.stdout.closed)

    def test_encoder_failure_reaps_blocked_renderer(self):
        child = subprocess.Popen([sys.executable, '-c', "import sys; sys.stdout.buffer.write(b'x' * 10000000)"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        with self.assertRaises(subprocess.CalledProcessError):
            render.encode_frames(child, [sys.executable, '-c', 'import sys; sys.exit(7)'])
        self.assertIsNotNone(child.returncode)
        self.assertTrue(child.stdout.closed)

    def test_renderer_failure_is_reported(self):
        child = subprocess.Popen([sys.executable, '-c', 'import sys; sys.exit(9)'], stdout=subprocess.PIPE)
        with self.assertRaises(SystemExit):
            render.encode_frames(child, [sys.executable, '-c', 'import sys; sys.stdin.buffer.read()'])
        self.assertEqual(child.returncode, 9)
        self.assertTrue(child.stdout.closed)


if __name__ == '__main__':
    unittest.main()
