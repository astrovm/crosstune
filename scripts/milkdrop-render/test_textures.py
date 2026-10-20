"""Tests for picking the images the presets draw with, on made-up presets and a made-up pack."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('milkdrop_textures', Path(__file__).with_name('textures.py'))
textures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(textures)


class TexturesTest(unittest.TestCase):
    def test_used_is_each_image_without_its_mode_and_not_projectms_own(self):
        preset = 'ret = tex2D(sampler_fw_Worms, uv) + tex2D(sampler_main, uv) + tex2D(sampler_pc_blur2, uv)\n' \
                 'ret += tex2D(sampler_noise_lq, uv) + tex2D(sampler_rand03_smalltiled, uv) + tex2D(sampler_cells, uv);'
        self.assertEqual({'worms', 'rand03_smalltiled', 'cells'}, textures.used(preset))

    def test_it_copies_the_used_ones_drops_the_rest_and_names_whats_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            pack, presets, out = tmp / 'pack', tmp / 'milkdrop', tmp / 'milkdrop-textures'
            for d in (pack, presets, out):
                d.mkdir()
            for name in ('worms.jpg', 'Cells.jpg', 'smalltiled_a.jpg', 'smalltiled_b.jpg', 'sky.jpg'):
                (pack / name).write_bytes(name.encode())
            (out / 'old.jpg').write_bytes(b'old')
            (presets / 'one.milk').write_text('sampler_fw_worms sampler_rand00 sampler_rand01_smalltiled')
            (presets / 'two.milk').write_text('sampler_cells sampler_rose')
            textures.PRESETS, textures.TEXTURES = presets, out
            sys.argv = ['textures.py', str(pack)]
            errors = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                textures.main()
            self.assertEqual(['Cells.jpg', 'smalltiled_a.jpg', 'smalltiled_b.jpg', 'worms.jpg'], sorted(f.name for f in out.iterdir()))
            self.assertEqual(b'worms.jpg', (out / 'worms.jpg').read_bytes())
            self.assertEqual('missing rose: two\n', errors.getvalue())


if __name__ == '__main__':
    unittest.main()
