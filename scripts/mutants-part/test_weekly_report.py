import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


LOST_32 = '**No results from part 32, so its mutants are not listed.** Rerun the failed jobs of the run.'


class WeeklyReport(unittest.TestCase):
    def run_report(self, present, contents="", jobs="success", parts=(32,), matcher="success"):
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
            gh.write_text('''#!/usr/bin/env python3
import sys
args = sys.argv[1:]
if args[:2] == ["issue", "create"]:
    print("https://example.invalid/issues/1")
if args[:2] in (["issue", "create"], ["issue", "comment"]):
    with open(args[args.index("--body-file") + 1]) as body, open("posted.md", "a") as posted:
        posted.write(body.read())
''')
            gh.chmod(0o755)
            for part in parts:
                if not present or part not in present:
                    continue
                file = root / f'results/mutants-part-{part}/_temp/lived.txt'
                file.parent.mkdir(parents=True)
                file.write_text(contents)
            env = {**os.environ, 'PATH': f"{root / 'bin'}:{os.environ['PATH']}",
                   'PARTS': json.dumps(list(parts)), 'JOBS': jobs, 'MATCHER': matcher, 'RUN': 'https://example.invalid/run',
                   'GITHUB_STEP_SUMMARY': str(root / 'summary'), 'GH_REPO': 'example/repo'}
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=root, env=env, capture_output=True, text=True)
            self.posted = (root / 'posted.md').read_text() if (root / 'posted.md').exists() else ''
            return result, (root / 'summary').read_text() if (root / 'summary').exists() else ''

    def test_nested_empty_lived_file_is_a_clean_part(self):
        result, summary = self.run_report((32,))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Every change in these parts was caught', summary)

    def test_nested_lived_rows_appear_in_the_report(self):
        result, summary = self.run_report((32,), 'Example.kt:3  survived: boundary\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('### Lived (1)', summary)
        self.assertIn('Example.kt:3  survived: boundary', summary)

    def test_missing_file_still_fails_when_another_part_failed(self):
        result, summary = self.run_report((), jobs='failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(LOST_32, summary)
        self.assertNotIn('Every change in these parts was caught', summary)

    def test_missing_lived_file_cannot_report_a_clean_part(self):
        result, summary = self.run_report(())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('expected one', result.stderr)
        self.assertNotIn('Every change in these parts was caught', summary)

    def test_lost_part_reaches_the_issue_even_with_no_rows(self):
        result, summary = self.run_report((), jobs='failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(LOST_32, summary)
        self.assertIn(LOST_32, self.posted)

    def test_only_the_lost_part_is_named_next_to_other_parts_rows(self):
        result, summary = self.run_report((33,), 'Example.kt:3  survived: boundary\n', parts=(32, 33))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.posted.count('No results from part'), 1)
        self.assertIn(LOST_32, self.posted)
        self.assertIn('Example.kt:3  survived: boundary', self.posted)

    def test_a_failed_matcher_run_reaches_the_issue(self):
        result, summary = self.run_report((32,), matcher='failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('The full MatcherCasesTest run ended failure', summary)
        self.assertIn('The full MatcherCasesTest run ended failure', self.posted)

    def test_a_clean_run_posts_nothing(self):
        result, _ = self.run_report((32,))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.posted, '')


if __name__ == '__main__':
    unittest.main()
