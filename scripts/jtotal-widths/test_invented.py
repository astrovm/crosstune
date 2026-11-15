import json
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import invented  # noqa: E402


class InventedTest(unittest.TestCase):
    def test_write_makes_one_shift_jis_sheet_for_each_stack(self):
        with tempfile.TemporaryDirectory() as tmp:
            invented.write(tmp)
            rows = json.loads((Path(tmp) / "urls.json").read_text(encoding="utf-8"))
            self.assertEqual([row["url"] for row in rows], ["invented:main", "invented:mono"])
            page = (Path(tmp) / "sheets" / "0001.html").read_bytes().decode("cp932")
            self.assertIn("tt { font-family: 'Inconsolata', monospace; }", page)
            self.assertIn("ｶﾞあい", page)
            # A letter Shift_JIS has no code for is an entity, as J-Total writes it.
            self.assertIn("&#1329;&#1329;&#1329;ab", page)
            self.assertEqual((Path(tmp) / "dat_st.css").read_text(encoding="utf-8"), "body { font-size: 15px; line-height: 1.618; font-family: sans-serif; }\n")

    def test_each_line_is_its_chord_over_its_words(self):
        self.assertIn("xx" + invented.chord("C") + "<br>\nｶﾞあい<br>\n", invented.page(invented.STACKS["main"]))

    def test_placed_reads_the_letter_each_chord_is_on(self):
        chars = [{"c": c, "left": 20.0 * i, "right": 20.0 * (i + 1)} for i, c in enumerate("ab")]
        drawing = [{"url": "invented:main", "x0": 0.0, "lines": [
            {"chars": [], "chords": [{"name": "C", "bass": None, "left": 20.0}]}, {"chars": chars, "chords": []}]}]
        self.assertEqual(invented.placed(drawing), {"main": [("ab", 1)]})

    def test_the_command_line_writes_and_shows(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(invented.main(["write", tmp]), 0)
            drawing = Path(tmp) / "d.json"
            drawing.write_text("[]", encoding="utf-8")
            self.assertEqual(invented.main(["show", str(drawing)]), 0)
        with unittest.mock.patch("sys.stderr"):
            self.assertEqual(invented.main([]), 2)


if __name__ == "__main__":
    unittest.main()
