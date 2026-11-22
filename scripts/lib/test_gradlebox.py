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

    def test_stdlib_uses_the_first_available_module_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            box = Path(directory) / 'box'
            jar = box / 'seed/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4/abc/kotlin-stdlib-2.4.jar'
            jar.parent.mkdir(parents=True)
            jar.touch()
            with mock.patch.dict(os.environ, {'GRADLE_BOX_DIR': str(box)}, clear=True):
                self.assertEqual(jar, gradlebox.kotlin_stdlib())
