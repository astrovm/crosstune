import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import gradlebox


class GradleBoxTest(unittest.TestCase):
    def test_box_dir_uses_environment_then_xdg_then_home(self):
        home = Path('/home/example')
        for env, expected in (
            ({'GRADLE_BOX_DIR': '/box', 'XDG_CACHE_HOME': '/xdg'}, Path('/box')),
            ({'XDG_CACHE_HOME': '/xdg'}, Path('/xdg/crosstune-gradle-box')),
            ({}, home / '.cache/crosstune-gradle-box'),
            ({'GRADLE_BOX_DIR': '', 'XDG_CACHE_HOME': ''}, home / '.cache/crosstune-gradle-box'),
        ):
            with self.subTest(env=env), mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual(expected, gradlebox.box_dir(home))

    def test_caches_include_existing_slots_seed_and_user_in_order(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            box = home / 'box'
            expected = [box / '1/caches/modules-2/files-2.1', box / 'seed/modules-2/files-2.1',
                        home / 'gradle/caches/modules-2/files-2.1']
            for path in expected:
                path.mkdir(parents=True)
            with mock.patch.dict(os.environ, {'GRADLE_BOX_DIR': str(box), 'GRADLE_USER_HOME': str(home / 'gradle')}, clear=True):
                self.assertEqual(expected, gradlebox.gradle_caches(home))

    def test_stdlib_is_the_version_the_app_builds_with_even_when_a_slot_keeps_an_older_one(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'app'
            root.mkdir()
            (root / 'build.gradle.kts').write_text('id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"\n', encoding='utf-8')
            box = Path(directory) / 'box'
            older = box / 'gradle-1/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.0/abc/kotlin-stdlib-2.3.0.jar'
            wanted = box / 'seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/def/kotlin-stdlib-2.4.21.jar'
            for jar in (older, wanted):
                jar.parent.mkdir(parents=True)
                jar.touch()
            with mock.patch.dict(os.environ, {'GRADLE_BOX_DIR': str(box), 'GRADLE_USER_HOME': str(Path(directory) / 'gradle')}, clear=True):
                self.assertEqual(wanted, gradlebox.kotlin_stdlib(root))
                wanted.unlink()
                with self.assertRaises(SystemExit) as missing:
                    gradlebox.kotlin_stdlib(root)
                self.assertIn('kotlin-stdlib-2.4.21.jar', str(missing.exception))

    def test_a_build_file_without_a_kotlin_version_is_named(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'build.gradle.kts').write_text('plugins {}\n', encoding='utf-8')
            with self.assertRaises(SystemExit):
                gradlebox.kotlin_version(directory)


if __name__ == '__main__':
    unittest.main()
