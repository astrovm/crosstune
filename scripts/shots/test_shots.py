"""Checks theme arguments and media publishing with real temporary files."""
import contextlib
import importlib.util
import io
import subprocess
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('shots', Path(__file__).with_name('shots.py'))
shots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shots)


class ArgumentsTest(unittest.TestCase):
    def test_default_regenerates_both_themes_in_every_language(self):
        args = shots.arguments([])
        self.assertEqual(args.theme, 'both')
        self.assertEqual(set(args.langs), set(shots.LANGS))

    def test_single_theme_and_language_selection(self):
        args = shots.arguments(['zh-tw', 'ja', '--theme', 'light'])
        self.assertEqual((args.langs, args.theme), (['zh-tw', 'ja'], 'light'))

    def test_invalid_selection_is_rejected(self):
        for args in [['../outside'], ['en', '--theme', 'sepia']]:
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                shots.arguments(args)
            self.assertEqual(error.exception.code, 2)


class PublishingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = shots.ROOT, shots.OUT
        shots.ROOT = Path(self.tmp.name)
        shots.OUT = shots.ROOT / 'rendered'
        self.images = {theme: shots.ROOT / f'{theme}.png' for theme in ('dark', 'light')}
        for theme, color in [('dark', 'black'), ('light', 'white')]:
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', f'color=c={color}:s=16x16', '-frames:v', '1', '-threads', '1', str(self.images[theme])], check=True)

    def tearDown(self):
        shots.ROOT, shots.OUT = self.previous
        self.tmp.cleanup()

    def fixture(self, theme, count=8):
        made = shots.OUT / 'zh-tw' / theme
        (made / 'store').mkdir(parents=True)
        for clip in ('home', 'lyrics'):
            (made / f'{clip}-{theme}.mp4').write_bytes(f'{theme} {clip}'.encode())
            (made / f'{clip}-{theme}.png').write_bytes(self.images[theme].read_bytes())
        for number in range(1, count + 1):
            (made / 'store' / f'{number}.png').write_bytes(self.images[theme].read_bytes())

    def test_both_themes_keep_separate_media_and_listing_sets(self):
        # Either publishing order must preserve the same two complete sets.
        for order in [('dark', 'light'), ('light', 'dark')]:
            with self.subTest(order=order):
                for theme in order:
                    made = shots.OUT / 'zh-tw' / theme
                    if not made.exists():
                        self.fixture(theme)
                    shots.publish('zh-tw', theme)
                for theme in order:
                    clip = shots.ROOT / 'docs/img/zh-tw' / f'home-{theme}.mp4'
                    self.assertEqual(clip.read_bytes(), f'{theme} home'.encode())
                    self.assertTrue(clip.with_suffix('.jpg').exists())
                dark = shots.ROOT / 'fastlane/metadata/android/zh-TW/images/phoneScreenshots'
                light = shots.ROOT / 'screenshots/light/zh-TW'
                self.assertEqual(len(list(dark.glob('*.png'))), 8)
                self.assertEqual(len(list(light.glob('*.png'))), 8)
                for folder, theme in [(dark, 'dark'), (light, 'light')]:
                    for shot in folder.glob('*.png'):
                        self.assertEqual(shot.read_bytes(), self.images[theme].read_bytes())
                self.assertEqual(len(list(dark.parent.iterdir())), 1)

    def test_incomplete_set_does_not_replace_store_listing(self):
        self.fixture('light', count=7)
        listing = shots.ROOT / 'fastlane/metadata/android/zh-TW/images/phoneScreenshots'
        listing.mkdir(parents=True)
        (listing / '1.png').write_bytes(b'existing dark screenshot')
        with self.assertRaisesRegex(RuntimeError, 'Expected 8 light screenshots'):
            shots.publish('zh-tw', 'light')
        self.assertEqual((listing / '1.png').read_bytes(), b'existing dark screenshot')
        self.assertFalse((shots.ROOT / 'screenshots/light/zh-TW').exists())


if __name__ == '__main__':
    unittest.main()
