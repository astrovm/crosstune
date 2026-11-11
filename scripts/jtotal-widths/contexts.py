#!/usr/bin/env python3
"""J-Total widths beside other scripts, on this machine's own fonts or in the table's container (Issue #830).

  python3 scripts/jtotal-widths/contexts.py codes CODES.json                      # the code points to probe
  python3 scripts/jtotal-widths/contexts.py measure OUT.json CODES.json [--mono] [--browser B] [--lang ja] [--fonts DIR]
  python3 scripts/jtotal-widths/contexts.py container OUT.json CODES.json [--mono]   # `measure` in the table's container
  python3 scripts/jtotal-widths/contexts.py diff A.json B.json [--show N]          # where two measurements differ
  python3 scripts/jtotal-widths/contexts.py sheets A.json B.json BROWSER.json...  # which of those letters the sheets use

`measure` draws each code point as `jtotal_widths.py measure` does: a span in the page's font stack at
40px, divided by one digit. It draws it in each context of CONTEXTS: between two letters of a script,
after or before one only, or alone. It runs in this machine's browser with this machine's fonts, so on
a Mac it measures macOS's fonts. B is chromium or webkit as Playwright ships them, or the path of a
Chromium-based browser. A Chromium browser also says which fonts drew each code point
(`CSS.getPlatformFontsForNode`), beside hiragana and beside Latin. WebKit has no such call.

The code points are in a file, so two machines with other Unicode data probe the same ones. `codes`
writes the set PR #880 probed (punctuation, symbols, marks, spaces and ambiguous-width letters of the
BMP, the C1 controls, and the letters above U+FFFF that aren't emoji), and every letter a Shift_JIS
page can hold, as J-Total's pages are Shift_JIS.

`diff` says, context by context, how many code points the two measurements draw at another width.
`sheets` counts the drawn sheets (`compare_sheets.py browser`) that use a code point `diff` finds.
Neither prints any words of a sheet: only code points and counts.
"""
import argparse
import json
import sys
import tempfile
import time
import unicodedata
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import jtotal_widths as w  # noqa: E402

# The letters around the one measured. The table is measured beside hiragana, as lyrics are Japanese.
CONTEXTS = {
    "hiragana": ("あ", "あ"),
    "katakana": ("ア", "ア"),
    "kanji": ("漢", "漢"),
    "hangul": ("한", "한"),
    "bopomofo": ("ㄅ", "ㄅ"),
    "latin": ("A", "A"),
    "greek": ("Ω", "Ω"),
    "cyrillic": ("Ж", "Ж"),
    "arabic": ("ب", "ب"),
    "hebrew": ("ש", "ש"),
    "thai": ("ก", "ก"),
    "devanagari": ("क", "क"),
    "armenian": ("Ա", "Ա"),
    "georgian": ("ა", "ა"),
    "fullwidth": ("Ａ", "Ａ"),
    "none": ("", ""),
    "hiragana before": ("あ", ""),
    "hiragana after": ("", "あ"),
    "latin before": ("A", ""),
    "latin after": ("", "A"),
}
BASE = "hiragana"
FONT_CONTEXTS = ("hiragana", "latin")  # where the browser is asked which font drew the letter
BATCH = 200


def in_cp932(code):
    """True for a letter a Shift_JIS page can hold without a numeric reference."""
    try:
        chr(code).encode("cp932")
    except UnicodeEncodeError:
        return False
    return True


def emoji(code):
    """The emoji blocks the width table measures on their own."""
    return 0x1F000 <= code <= 0x1FAFF


def probed(code):
    """True for a code point in the set: the one PR #880 probed, or one a Shift_JIS page can hold."""
    letter = chr(code)
    category = unicodedata.category(letter)
    if category in ("Cn", "Cs", "Co") or code < 0x80:
        return False
    if 0x80 <= code <= 0x9F or code > 0xFFFF:
        return not emoji(code)
    return category[0] in "PSMZ" or unicodedata.east_asian_width(letter) == "A" or in_cp932(code)


def codes():
    return [code for code in range(0x80, 0x110000) if probed(code)]


def rows(batch):
    return "".join(
        f'<div><tt data-c="{code}" data-x="{x}">{left}<s>{chr(code)}</s>{right}</tt></div>'
        for code in batch for x, (left, right) in CONTEXTS.items()
    )


MEASURE_JS = """() => {
  const probe = document.createElement('tt'); probe.textContent = '0'; document.body.appendChild(probe);
  const digit = probe.getBoundingClientRect().width;
  const out = {};
  for (const tt of document.querySelectorAll('tt[data-c]')) {
    (out[tt.dataset.x] ||= {})[tt.dataset.c] = Math.round(tt.querySelector('s').getBoundingClientRect().width / digit * 1000);
  }
  return {digit, out};
}"""


def platform_fonts(cdp, selector):
    """The fonts the browser drew each node of `selector` with, in document order."""
    root = cdp.send("DOM.getDocument", {"depth": 0})["root"]["nodeId"]
    nodes = cdp.send("DOM.querySelectorAll", {"nodeId": root, "selector": selector})["nodeIds"]
    return [sorted({f["familyName"] for f in cdp.send("CSS.getPlatformFontsForNode", {"nodeId": node})["fonts"]}) for node in nodes]


def measure(out, code_list, stack, fonts, browser_name, lang=None):
    from playwright.sync_api import sync_playwright

    head = w.page_head(stack, fonts, lang)
    widths = {x: {} for x in CONTEXTS}
    used = {x: {} for x in FONT_CONTEXTS}
    began = time.monotonic()
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser = w.launch(p, browser_name, lang)
        environment = w.environment(browser, browser_name if "/" not in browser_name else Path(browser_name).stem, stack, fonts)
        environment.append(f"Page language {lang or 'not set'}, browser language {lang or 'as Playwright starts it'}")
        page = browser.new_page(viewport={"width": 1200, "height": 800}, locale=lang)
        cdp = page.context.new_cdp_session(page) if browser_name != "webkit" else None
        if cdp:
            cdp.send("DOM.enable")
            cdp.send("CSS.enable")
        html = Path(tmp) / "contexts.html"
        digit = None
        for start in range(0, len(code_list), BATCH):
            batch = code_list[start:start + BATCH]
            html.write_text(head + rows(batch) + "</body></html>", encoding="utf-8")
            page.goto(html.as_uri())
            page.evaluate("document.fonts.ready")
            found = page.evaluate(MEASURE_JS)
            digit = found["digit"]
            for context, values in found["out"].items():
                widths[context].update({int(c): v for c, v in values.items()})
            if cdp:
                for context in FONT_CONTEXTS:
                    used[context].update(zip(batch, platform_fonts(cdp, f'tt[data-x="{context}"] s')))
            if start // BATCH % 50 == 0:
                print(f"{start + len(batch)} of {len(code_list)} code points, {time.monotonic() - began:.0f} s", file=sys.stderr, flush=True)
        neighbours = {}
        if cdp:
            html.write_text(head + "".join(f'<div><tt data-n="{x}">{a}{b}</tt></div>' for x, (a, b) in CONTEXTS.items()) + "</body></html>", encoding="utf-8")
            page.goto(html.as_uri())
            page.evaluate("document.fonts.ready")
            neighbours = dict(zip(CONTEXTS, platform_fonts(cdp, "tt[data-n]")))
        browser.close()
    environment.append(f"One digit is {digit} CSS px at 40px")
    data = {
        "environment": environment, "stack": stack,
        "contexts": {x: {str(c): v for c, v in m.items()} for x, m in widths.items()},
        "fonts": {x: {str(c): f for c, f in m.items()} for x, m in used.items()},
        "neighbour_fonts": neighbours,
    }
    Path(out).write_text(json.dumps(data), encoding="utf-8")
    print(f"{out}: {len(code_list)} code points in {len(CONTEXTS)} contexts, {time.monotonic() - began:.0f} s", file=sys.stderr)


def load(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    data["contexts"] = {x: {int(c): v for c, v in m.items()} for x, m in data["contexts"].items()}
    data["fonts"] = {x: {int(c): f for c, f in m.items()} for x, m in data.get("fonts", {}).items()}
    return data


def differences(a, b):
    """{context: {code: (width in a, width in b)}} where the two measurements differ."""
    return {
        x: {c: (a["contexts"][x][c], b["contexts"][x][c]) for c in sorted(a["contexts"][x]) if c in b["contexts"].get(x, {}) and a["contexts"][x][c] != b["contexts"][x][c]}
        for x in a["contexts"]
    }


def columns(thousandths):
    return f"{thousandths / 1000:.3f}"


def diff_main(a_path, b_path, show):
    a, b = load(a_path), load(b_path)
    found = differences(a, b)
    print(f"A: {a_path}", *[f"  {e}" for e in a["environment"]], f"B: {b_path}", *[f"  {e}" for e in b["environment"]], sep="\n")
    print("\nCode points drawn at another width, per context (all probed, then those a Shift_JIS page can hold):")
    for context, codes_found in found.items():
        cp932 = [c for c in codes_found if in_cp932(c)]
        print(f"  {context:16} {len(a['contexts'][context]):6d} probed, {len(codes_found):6d} differ, {len(cp932):5d} in cp932")
    if show:
        print(f"\nShift_JIS code points that differ beside hiragana or Latin (first {show}): code point, A width, B width, B font")
        rows_out = sorted({c for x in ("hiragana", "latin") for c in found[x] if in_cp932(c)})
        for code in rows_out[:show]:
            for x in ("hiragana", "latin"):
                if code in found[x]:
                    va, vb = found[x][code]
                    font = ", ".join(b["fonts"].get(x, {}).get(code, [])) or "-"
                    print(f"  U+{code:04X} {x:8} {columns(va)} {columns(vb)}  {font}")


def sheet_letters(pages):
    """Counter of (stack, code point): how many sheets use each code point above U+007F."""
    used = Counter()
    for page in pages:
        stack = "mono" if page["font"] == "Inconsolata, monospace" else "main"
        letters = {ord(char["c"][0]) for line in page["lines"] for char in line["chars"] if ord(char["c"][0]) >= 0x80}
        used.update((stack, code) for code in letters)
    return used


def sheets_main(main_pair, mono_pair, browser_files):
    diffs = {"main": differences(*map(load, main_pair)), "mono": differences(*map(load, mono_pair))}
    pages = [page for path in browser_files for page in json.loads(Path(path).read_text(encoding="utf-8"))]
    used = sheet_letters(pages)
    print(f"{len(pages)} sheets")
    for (stack, code), count in sorted(used.items(), key=lambda item: (-item[1], item[0])):
        contexts = [x for x in CONTEXTS if code in diffs[stack][x]]
        if contexts:
            print(f"  {stack:4} U+{code:04X} on {count} sheets, differs in {len(contexts)} contexts: {', '.join(contexts)}")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("codes", "measure", "container", "diff", "sheets"))
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--mono", action="store_true")
    parser.add_argument("--browser", default="chromium")
    parser.add_argument("--lang")
    parser.add_argument("--fonts", type=Path, help="the folder with J-Total's three Inconsolata files")
    parser.add_argument("--show", type=int, default=0)
    args = parser.parse_args(argv)
    stack = w.MONO_STACK if args.mono else w.STACK
    if args.command == "codes":
        args.paths[0].write_text(json.dumps(codes()), encoding="utf-8")
    elif args.command == "measure":
        measure(args.paths[0], json.loads(args.paths[1].read_text(encoding="utf-8")), stack, args.fonts or w.container_fonts(), args.browser, args.lang)
    elif args.command == "container":
        out, code_file = (path.resolve() for path in args.paths[:2])
        if out.parent != code_file.parent:
            parser.error("OUT and CODES must be in one folder, which the container mounts")
        w.run_in_container(out.parent, ["/script/contexts.py", "measure", f"/work/{out.name}", f"/work/{code_file.name}", "--fonts", "/fonts", *(["--mono"] if args.mono else [])])
    elif args.command == "diff":
        diff_main(args.paths[0], args.paths[1], args.show)
    else:
        if len(args.paths) < 5:
            parser.error("sheets needs MAIN-A MAIN-B MONO-A MONO-B and at least one browser.json")
        sheets_main(args.paths[0:2], args.paths[2:4], args.paths[4:])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
