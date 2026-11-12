#!/usr/bin/env python3
"""Made-up J-Total sheets, one on each font stack, for the chords the app's tests place.

  python3 scripts/jtotal-widths/invented.py write DIR          # DIR/sheets, DIR/urls.json, DIR/dat_st.css
  python3 scripts/jtotal-widths/compare_sheets.py draw DIR --fonts FONTS [--lang ja] [--out NAME.json]
  python3 scripts/jtotal-widths/invented.py show DIR/NAME.json  # each line, the letter each stack puts its chord on

The words are invented, so the pages may go anywhere, the Windows workflow's artifact too. Each line is
a chord line over a line of words. A test in ChordSheetsTest or ChordFinderTest expects the letter a
browser puts the chord on, and `show` prints that letter, as the browser drew it, for each stack.
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

STACKS = {"main": "'Inconsolata','MS Gothic','Hiragino Kaku Gothic','Monaco',sans-serif", "mono": "'Inconsolata', monospace"}


def chord(name):
    return f"""<a href="JavaScript:jump_1('../../../cd/{name}.html');">{name}</a>"""


# Each line: what comes before the chord on its line, and the words under it.
LINES = [
    ("x" * 41, "あ" * 22),
    # A digit the stacks draw at another width, and that runs left to right, as Hebrew does not.
    ("x" * 11, "&#1776;" * 20 + "あ"),
    ("xx", "ｶﾞあい"),
    ("xx", "ﾊﾟあい"),
    ("xxxx", "ｶﾞｶﾞあい"),
    ("　", "」　い"),
    ("abcd", "あ、　「いう」　えお"),
    ("abcdefgh", "「あい」　「うえ」お"),
    ("abcdefgh", "※※※※aaaa"),
    ("abcdef", "※A　ああ"),
    ("x" * 6, "αβγδεあ"),
    ("x" * 6, "жизньあ"),
    ("xxxx", "№≒あい"),
    ("x" * 6, "≒≒≒≒あ"),
    ("abcd", "ԱԱԱab"),
    ("abcd", "აააააab"),
    ("abcd", "Ĳĳŉﾠab"),
    ("abcd", "αααααb"),
    ("abcdefghij", "аааааааааb"),
    ("abcdefghij", "가가가가가bc"),
    ("abcde", "😀😀abc"),
    ("abcdef", "あ）　い"),
    ("abcdef", "あ）：い"),
]


def page(stack):
    body = "<br>\n".join(f"{before}{chord('C')}<br>\n{words}<br>\n" for before, words in LINES)
    return ('<html><head><meta charset="shift_jis"><link href="/ssi_dat/dat_st.css" rel="stylesheet" type="text/css">'
            f"<style>tt {{ font-family: {stack}; }}</style></head><body><tt><!--HPSTART-->{body}<!--HPEND--></tt></body></html>")


def write(directory):
    directory = Path(directory)
    (directory / "sheets").mkdir(parents=True, exist_ok=True)
    rows = []
    for number, (name, stack) in enumerate(STACKS.items()):
        file = f"{number:04}.html"
        # A letter Shift_JIS can't hold is written as an entity, as J-Total writes it.
        (directory / "sheets" / file).write_bytes(page(stack).encode("cp932", "xmlcharrefreplace"))
        rows.append({"file": file, "url": f"invented:{name}"})
    (directory / "urls.json").write_text(json.dumps(rows, indent=1) + "\n", encoding="utf-8")
    # The page's own stylesheet sets no font for `tt`: the style on the page does.
    (directory / "dat_st.css").write_text("", encoding="utf-8")


def placed(drawing):
    """For each stack, each line's words and the letter its chord is on, as the browser drew them."""
    from compare_sheets import expected
    return {page["url"].removeprefix("invented:"): [(line["text"], line["chords"][0][0]) for line in expected(page)] for page in drawing}


def main(argv):
    if len(argv) == 2 and argv[0] == "write":
        write(argv[1])
        return 0
    if len(argv) == 2 and argv[0] == "show":
        found = placed(json.loads(Path(argv[1]).read_text(encoding="utf-8")))
        for stack, lines in found.items():
            for text, letter in lines:
                print(f"{stack}\t{letter}\t{text}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
