import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mutants', Path(__file__).with_name('mutants.py'))
mutants = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mutants)


class ChangedLines(unittest.TestCase):
    def test_play_and_main_sources_keep_the_package_path(self):
        diff = '''+++ b/app/src/main/java/com/example/Main.kt
@@ -1 +2,2 @@
+++ b/app/src/play/java/com/example/Cast.kt
@@ -0,0 +1 @@
'''
        with patch.object(mutants.subprocess, 'run', side_effect=[
            subprocess.CompletedProcess([], 0, stdout='abc\n'),
            subprocess.CompletedProcess([], 0, stdout=diff),
        ]) as run:
            self.assertEqual(mutants.changed_lines('origin/main'), [
                'com/example/Main.kt:2', 'com/example/Main.kt:3', 'com/example/Cast.kt:1',
            ])
        self.assertIn('app/src/play/java/', run.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
