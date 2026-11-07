import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class WeeklyReport(unittest.TestCase):
    def run_report(self, present, contents=""):
        workflow = (ROOT / '.github/workflows/mutants-weekly.yml').read_text()
        step = workflow.split('      - name: List what lived in the open issue\n', 1)[1]
        script = step.split('        run: |\n', 1)[1]
        script = '\n'.join(line[10:] for line in script.splitlines()) + '\n'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copy(ROOT / 'scripts/mutants-post.sh', root / 'scripts/mutants-post.sh')
            (root / 'bin').mkdir()
            jq = root / 'bin/jq'
            jq.write_text('''#!/usr/bin/env python3
import json, sys
parts = json.load(sys.stdin)
print("\\n".join(map(str, parts)) if sys.argv[-1] == ".[]" else ", ".join(map(str, parts)))
''')
            jq.chmod(0o755)
            gh = root / 'bin/gh'
            gh.write_text('#!/bin/sh\n[ "$1 $2" != "issue create" ] || echo https://example.invalid/issues/1\nexit 0\n')
            gh.chmod(0o755)
            if present:
                file = root / 'results/mutants-part-32/_temp/lived.txt'
                file.parent.mkdir(parents=True)
                file.write_text(contents)
            env = {**os.environ, 'PATH': f"{root / 'bin'}:{os.environ['PATH']}",
                   'PARTS': json.dumps([32]), 'JOBS': 'success', 'RUN': 'https://example.invalid/run',
                   'GITHUB_STEP_SUMMARY': str(root / 'summary'), 'GH_REPO': 'example/repo'}
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=root, env=env, capture_output=True, text=True)
            return result, (root / 'summary').read_text() if (root / 'summary').exists() else ''

    def test_nested_empty_lived_file_is_a_clean_part(self):
        result, summary = self.run_report(True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Every change in these parts was caught', summary)

    def test_nested_lived_rows_appear_in_the_report(self):
        result, summary = self.run_report(True, 'Example.kt:3  survived: boundary\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('### Lived (1)', summary)
        self.assertIn('Example.kt:3  survived: boundary', summary)

    def test_missing_lived_file_cannot_report_a_clean_part(self):
        result, summary = self.run_report(False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('expected one', result.stderr)
        self.assertNotIn('Every change in these parts was caught', summary)


if __name__ == '__main__':
    unittest.main()
