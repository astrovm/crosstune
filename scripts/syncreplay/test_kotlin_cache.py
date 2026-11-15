"""Compiler inputs and published classes stay correct across overlapping replay runs."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import subprocess
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('cache_replay', Path(__file__).with_name('syncreplay.py'))
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)


class ReplayCacheTest(unittest.TestCase):
    def test_one_destination_has_one_explicit_mount_with_the_required_access(self):
        for modes in (('ro', 'rw'), ('rw', 'ro'), ('ro', 'ro')):
            command = r.replay_container((Path('/output'), Path('/output'), modes[0]),
                                         (Path('/output'), Path('/output'), modes[1]))
            self.assertEqual(1, command.count('-v'))
            self.assertIn('/output:/output:' + ('rw' if 'rw' in modes else 'ro'), command)
        with self.assertRaises(ValueError):
            r.replay_container((Path('/one'), Path('/output'), 'ro'), (Path('/two'), Path('/output'), 'rw'))

    def test_mount_aliases_cannot_repeat_a_destination_or_change_its_access_silently(self):
        command = r.replay_container((Path('/output'), Path('/output'), 'ro'),
                                     (Path('/output/../output'), Path('/output/../output'), 'rw'))
        self.assertEqual(1, command.count('-v'))
        self.assertIn('/output:/output:rw', command)
        with self.assertRaises(ValueError):
            r.replay_container((Path('/output'), Path('/output'), 'unexpected'))

    def test_an_override_with_changed_snapshot_bytes_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)):
            source = Path(folder) / 'Source.kt'
            source.write_text('first')
            copied = r.timing_copied(source, [source])[0]
            copied.write_text('changed')
            with self.assertRaisesRegex(ValueError, 'changed immutable timing input'):
                r.timing_copied(source, [source])

    def test_a_failed_compiler_publishes_nothing_and_the_next_call_can_retry(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)):
            source = Path(folder) / 'Source.kt'
            source.write_text('fake')
            calls = []

            def compile(command, **_):
                output = Path(command[command.index('-d') + 1])
                calls.append(output)
                (output / 'compiled.txt').write_text('partial' if len(calls) == 1 else 'complete')
                if len(calls) == 1:
                    raise subprocess.CalledProcessError(1, command)

            with patch.object(r, 'kotlin_jars', return_value=[Path('/compiler/kotlin-stdlib-2.jar')]),                     patch.object(r.subprocess, 'run', side_effect=compile):
                with self.assertRaises(subprocess.CalledProcessError):
                    r.untimed_classes([source])
                self.assertEqual([], list((Path(folder) / 'untimed-classes-v2').iterdir()))
                classes = r.untimed_classes([source])
            self.assertEqual('complete', (classes / 'compiled.txt').read_text())
            self.assertTrue((classes / 'done').exists())

    def test_a_delayed_compiler_reads_the_override_used_for_its_cache_key(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)):
            first, second = (Path(folder) / name for name in ('first.kt', 'second.kt'))
            first.write_text('first')
            second.write_text('second')
            copied = r.timing_copied(first, [first])
            seen = []

            def compile(command, **_):
                # A second caller arrives after the first key was made, before the compiler reads its source.
                r.timing_copied(second, [second])
                source = Path(command[-1]).read_text()
                seen.append(source)
                output = Path(command[command.index('-d') + 1])
                output.mkdir(parents=True, exist_ok=True)
                (output / 'compiled.txt').write_text(source)

            with patch.object(r, 'kotlin_jars', return_value=[Path('/compiler/kotlin-stdlib-2.jar')]), \
                    patch.object(r.subprocess, 'run', side_effect=compile):
                a = r.untimed_classes(copied)
                b = r.untimed_classes(r.timing_copied(second, [second]))
                again = r.untimed_classes(r.timing_copied(first, [first]))
            self.assertEqual(['first', 'second'], seen)
            self.assertEqual('first', (a / 'compiled.txt').read_text())
            self.assertEqual('second', (b / 'compiled.txt').read_text())
            self.assertEqual(a, again)
            self.assertNotEqual(a, b)

    def test_overlapping_builders_publish_complete_classes_from_separate_folders(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)):
            source = Path(folder) / 'Source.kt'
            source.write_text('fake')
            barrier = threading.Barrier(2)
            destinations = []

            def compile(command, **_):
                output = Path(command[command.index('-d') + 1])
                destinations.append(output)
                output.mkdir(parents=True, exist_ok=True)
                barrier.wait(timeout=10)
                (output / 'compiled.txt').write_text('complete')

            with patch.object(r, 'kotlin_jars', return_value=[Path('/compiler/kotlin-stdlib-2.jar')]), \
                    patch.object(r.subprocess, 'run', side_effect=compile), ThreadPoolExecutor(2) as pool:
                answers = list(pool.map(lambda _: r.untimed_classes([source]), range(2)))
            self.assertEqual(2, len(set(destinations)))
            self.assertEqual(answers[0], answers[1])
            self.assertEqual('complete', (answers[0] / 'compiled.txt').read_text())
            self.assertTrue((answers[0] / 'done').exists())
            self.assertFalse(any(path.exists() for path in destinations))

    def test_old_unverified_classes_are_not_reused(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'OUT', Path(folder)):
            source = Path(folder) / 'Source.kt'
            source.write_text('fake')
            old_key = hashlib.sha256(source.read_bytes() + r.kotlinc.LOCK.read_bytes()).hexdigest()[:16]
            old = Path(folder) / 'untimed-classes' / old_key
            old.mkdir(parents=True)
            (old / 'done').write_text('')
            (old / 'compiled.txt').write_text('wrong')

            def compile(command, **_):
                output = Path(command[command.index('-d') + 1])
                output.mkdir(parents=True, exist_ok=True)
                (output / 'compiled.txt').write_text('right')

            with patch.object(r, 'kotlin_jars', return_value=[Path('/compiler/kotlin-stdlib-2.jar')]), \
                    patch.object(r.subprocess, 'run', side_effect=compile):
                classes = r.untimed_classes([source])
            self.assertNotEqual(old, classes)
            self.assertEqual('right', (classes / 'compiled.txt').read_text())
