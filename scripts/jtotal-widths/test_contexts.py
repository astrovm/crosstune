import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import contexts as x  # noqa: E402
import jtotal_widths as w  # noqa: E402


def measurement(widths, fonts=None):
    """A measurement in every context, with `widths` {code: width} in each."""
    return {"environment": ["test"], "stack": w.STACK, "contexts": {c: dict(widths) for c in x.CONTEXTS}, "fonts": fonts or {}}


class SetTest(unittest.TestCase):
    def test_a_shift_jis_letter_is_told_apart(self):
        self.assertTrue(x.in_cp932(0x203B))
        self.assertFalse(x.in_cp932(0x0E01))

    def test_the_set_is_marks_symbols_spaces_ambiguous_letters_c1_and_shift_jis(self):
        for code in (0x80, 0x9F, 0x203B, 0x0301, 0x3000, 0x3B1, 0x4E00, 0x1D300):
            self.assertTrue(x.probed(code), hex(code))

    def test_ascii_unassigned_private_use_emoji_and_other_letters_are_left_out(self):
        for code in (0x41, 0x378, 0xE000, 0xD800, 0x1F600, 0x0E01):
            self.assertFalse(x.probed(code), hex(code))

    def test_codes_is_the_set_in_order(self):
        codes = x.codes()
        self.assertEqual(codes, sorted(codes))
        self.assertIn(0x203B, codes)
        self.assertNotIn(0x1F600, codes)

    def test_each_code_point_is_drawn_in_each_context(self):
        html = x.rows([0x203B])
        self.assertEqual(html.count("<tt "), len(x.CONTEXTS))
        self.assertIn('<tt data-c="8251" data-x="latin">A<s>※</s>A</tt>', html)
        self.assertIn('<tt data-c="8251" data-x="none"><s>※</s></tt>', html)


class DiffTest(unittest.TestCase):
    def test_only_differences_in_contexts_both_measured(self):
        a = measurement({0x203B: 2000, 0x3B1: 1216})
        b = measurement({0x203B: 1201, 0x3B1: 1216})
        del b["contexts"]["thai"]
        found = x.differences(a, b)
        self.assertEqual(found["hiragana"], {0x203B: (2000, 1201)})
        self.assertEqual(found["thai"], {})

    def test_columns_are_three_decimals(self):
        self.assertEqual(x.columns(1201), "1.201")

    def test_diff_and_sheets_print_code_points_and_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            a = folder / "a.json"
            b = folder / "b.json"
            drawn = folder / "browser.json"
            a.write_text(json.dumps(measurement({0x203B: 2000, 0x3B1: 1216, 0x1D300: 2000})), encoding="utf-8")
            b.write_text(json.dumps(measurement({0x203B: 1201, 0x3B1: 1216, 0x1D300: 1800}, {"hiragana": {0x203B: ["Monaco"]}})), encoding="utf-8")
            drawn.write_text(json.dumps([{"font": "x", "lines": [{"chars": [{"c": "※"}, {"c": "a"}, {"c": "α"}]}]}]), encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                x.diff_main(a, b, 5)
                x.sheets_main((a, b), (a, b), [drawn])
            text = out.getvalue()
        self.assertIn("U+203B hiragana 2.000 1.201  Monaco", text)
        self.assertIn("U+203B latin    2.000 1.201  -", text)
        self.assertNotIn("U+1D300 hiragana", text)
        self.assertIn("main U+203B on 1 sheets, differs in 20 contexts", text)
        self.assertNotIn("U+03B1 on", text)

    def test_sheet_letters_count_each_sheet_once(self):
        pages = [
            {"font": "Inconsolata, monospace", "lines": [{"chars": [{"c": "※"}, {"c": "※"}, {"c": "a"}]}]},
            {"font": "x", "lines": [{"chars": [{"c": "※"}]}]},
        ]
        self.assertEqual(x.sheet_letters(pages), {("mono", 0x203B): 1, ("main", 0x203B): 1})


class MainTest(unittest.TestCase):
    def test_codes_writes_the_set(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "codes.json"
            with mock.patch.object(x, "codes", return_value=[0x203B]):
                x.main(["codes", str(out)])
            self.assertEqual(json.loads(out.read_text(encoding="utf-8")), [0x203B])

    def test_measure_takes_the_stack_and_the_code_file(self):
        with tempfile.TemporaryDirectory() as folder:
            codes = Path(folder) / "codes.json"
            codes.write_text("[8251]", encoding="utf-8")
            with mock.patch.object(x, "measure") as measure:
                x.main(["measure", "out.json", str(codes), "--mono", "--fonts", folder, "--lang", "ja"])
            measure.assert_called_once_with(Path("out.json"), [8251], w.MONO_STACK, Path(folder), "chromium", "ja")

    def test_container_mounts_the_folder_of_both_files(self):
        with mock.patch.object(w, "run_in_container") as run:
            x.main(["container", "/a/out.json", "/a/codes.json"])
        run.assert_called_once_with(Path("/a"), ["/script/contexts.py", "measure", "/work/out.json", "/work/codes.json", "--fonts", "/fonts"])
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            x.main(["container", "/a/out.json", "/b/codes.json"])

    def test_sheets_needs_both_pairs_and_a_drawing(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            x.main(["sheets", "a", "b", "c"])


class HelpersTest(unittest.TestCase):
    def test_the_page_asks_for_the_stack_and_the_three_files(self):
        head = w.page_head(w.STACK, Path("/fonts"), "ja")
        self.assertIn('<html lang="ja">', head)
        self.assertIn(f"tt {{ font-family:{w.STACK}; font-size:40px;", head)
        self.assertEqual(head.count("@font-face"), 3)
        self.assertIn('src:url("file:///fonts/inconsolata-latin.woff2")', head)
        self.assertIn("<html>", w.page_head(w.STACK, Path("/fonts")))

    def test_a_browser_language_reaches_chromium_but_not_webkit(self):
        p = mock.Mock()
        w.launch(p, "chromium", "ja")
        p.chromium.launch.assert_called_once_with(headless=True, args=["--lang=ja"])
        w.launch(p, "webkit", "ja")
        p.webkit.launch.assert_called_once_with(headless=True, args=[])

    def test_another_chromium_browser_is_started_by_its_path(self):
        p = mock.Mock()
        with tempfile.NamedTemporaryFile() as brave:
            w.launch(p, brave.name)
        p.chromium.launch.assert_called_once_with(headless=True, executable_path=brave.name, args=[])
        with self.assertRaises(SystemExit):
            w.launch(p, "/no/such/browser")

    def test_the_environment_names_browser_fonts_and_files(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in w.FONTS:
                (Path(folder) / f"inconsolata-{name}.woff2").write_bytes(b"")
            browser = mock.Mock(version="156.0")
            with mock.patch.object(w.metadata, "version", return_value="1.64.1"), \
                    mock.patch.object(w, "font_packages", return_value=["fonts-x 1"]), \
                    mock.patch.object(w, "ALIASES", Path(folder) / "local.conf"):
                (Path(folder) / "local.conf").write_text(
                    '<fontconfig>\n<alias binding="same"><family>MS Gothic</family><prefer><family>Noto Sans Mono CJK JP</family></prefer></alias>\n'
                    '<alias binding="same"><family>monospace</family><prefer><family>Noto Sans Mono CJK JP</family></prefer></alias>\n</fontconfig>\n', encoding="utf-8")
                found = w.environment(browser, "chromium", w.STACK, folder)
        empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        self.assertEqual(found[1:3], ["Chromium 156.0", "Playwright 1.64.1"])
        self.assertEqual(found[4], "fonts-x 1")
        self.assertIn(f"latin={empty}", found[5])
        self.assertEqual(found[6], "Fontconfig aliases: MS Gothic is Noto Sans Mono CJK JP; monospace is Noto Sans Mono CJK JP")

    def test_font_packages_say_what_the_machine_is(self):
        with mock.patch.object(w.shutil, "which", side_effect=lambda tool: tool == "dpkg-query"), \
                mock.patch.object(w.subprocess, "check_output", return_value="fonts-a 1 installed\n"):
            self.assertEqual(w.font_packages(), ["fonts-a 1"])
        with mock.patch.object(w.shutil, "which", side_effect=lambda tool: tool == "sw_vers"), \
                mock.patch.object(w.subprocess, "check_output", return_value="26B123\n"), \
                mock.patch.object(w.platform, "mac_ver", return_value=("27.2", "", "")), \
                mock.patch.object(w.platform, "machine", return_value="arm64"):
            self.assertEqual(w.font_packages(), ["macOS 27.2 (26B123) arm64, its own fonts"])
        with mock.patch.object(w.shutil, "which", return_value=None):
            self.assertIn("no dpkg-query", w.font_packages()[0])

    def test_the_container_gets_the_work_folder_fonts_and_scripts(self):
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(w, "cache", return_value=Path(folder)), \
                mock.patch.object(w.urllib.request, "urlopen") as urlopen, \
                mock.patch.object(w.subprocess, "run") as run:
            urlopen.return_value.read.return_value = b"font"
            w.run_in_container(Path("/work-here"), ["/script/x.py"])
            self.assertEqual(urlopen.call_count, 3)
            w.container_fonts()
            self.assertEqual(urlopen.call_count, 3)
        command = run.call_args_list[-1].args[0]
        self.assertEqual(command[:4], ["podman", "run", "--rm", "-v"])
        self.assertIn("/work-here:/work", command)
        self.assertEqual(command[-2:], ["python3", "/script/x.py"])


if __name__ == "__main__":
    unittest.main()
