#!/usr/bin/env python3
"""How wide each letter is on J-Total's page, measured in Chromium, as the table the app places chords with.

  python3 scripts/jtotal-widths/jtotal_widths.py generate    # measure in a container, rewrite the table
  python3 scripts/jtotal-widths/jtotal_widths.py check       # measure again, and say where the table differs

J-Total sets a sheet in `Inconsolata, MS Gothic, Hiragino Kaku Gothic, Monaco, sans-serif`. A letter
Inconsolata has is one column, and one the Japanese monospace font has is one or two. Any other letter
Chromium draws from a proportional font, so its width is not a whole number: U+0133 is 0.888 of a
column and U+0132 is 1.470. `ChordSheets` counts chords over those widths, so it needs the real ones.

The page is measured with the page's own CSS and fonts: each letter is a span in `あXあ`, as lyrics are
Japanese, and its width is divided by the width of a digit. The table lists, in thousandths of a column,
every letter from U+0080 up (and emoji) that is not two columns, which is what the app assumes for
the rest. It is the browser's answer in the container of the Containerfile: another machine's fonts
give other widths for the scripts they draw differently, and the app can only follow one.

The table ends up in app/src/main/resources/com/astrovm/crosstune/jtotal-widths.txt, one run per
line: `FIRST[-LAST] WIDTH`, code points in hex.

The container stands in for Windows, where the sheets are written: MS Gothic draws Japanese on both
stacks, as Windows' Japanese fixed-width font is MS Gothic too. The Containerfile sends both names to
Noto Sans Mono CJK JP. macOS draws a different page, because the stack's "Hiragino Kaku Gothic" is not
a macOS font name, and Monaco then draws marks such as U+203B at 1.201 columns (Issue #830).
`contexts.py` measures any machine's browser against the table, and `compare_sheets.py moves` counts
the chords that land on another letter.
"""
import argparse
import hashlib
import json
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.request
from importlib import metadata
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1] if len(HERE.parents) > 1 else HERE  # in the container the script sits at /script
TABLE = REPO / "app/src/main/resources/com/astrovm/crosstune/jtotal-widths.txt"
MONO_TABLE = TABLE.with_name("jtotal-widths-mono.txt")
IMAGE = "localhost/crosstune-jtotal-widths"
ALIASES = Path("/etc/fonts/local.conf")  # where the Containerfile names the fonts the page asks for

# Google Fonts' Inconsolata v37, in the three files J-Total's stylesheet asks for, with their ranges.
FONT_BASE = "https://fonts.gstatic.com/s/inconsolata/v37/QldgNThLqRwH-OJ1UHjlKENVzkWGVkL3GZQmAwLYxYWI2qfdm7Lpp4U8WR"
FONTS = {
    "vi": (
        FONT_BASE + "L2l2eY.woff2",
        "U+0102-0103, U+0110-0111, U+0128-0129, U+0168-0169, U+01A0-01A1, U+01AF-01B0, U+0300-0301, U+0303-0304, U+0308-0309, U+0323, U+0329, U+1EA0-1EF9, U+20AB",
    ),
    "ext": (
        FONT_BASE + "P2l2eY.woff2",
        "U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304, U+0308, U+0329, U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C4, U+2113, U+2C60-2C7F, U+A720-A7FF",
    ),
    "latin": (
        FONT_BASE + "32lw.woff2",
        "U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD",
    ),
}
STACK = '"Inconsolata","MS Gothic","Hiragino Kaku Gothic","Monaco",sans-serif'
MONO_STACK = '"Inconsolata",monospace'

# Where lyrics can have letters: all of the Basic Multilingual Plane above Latin-1's controls, and emoji.
SPANS = ((0x80, 0xD7FF), (0xE000, 0xFFFD), (0x1F000, 0x1FAFF))
TWO = 2000
BATCH = 600


def spans_codes():
    return [c for lo, hi in SPANS for c in range(lo, hi + 1) if not 0x80 <= c <= 0x9F]


def unassigned(code):
    """True where Unicode gives the code point no letter, so the table may take any width there."""
    return unicodedata.category(chr(code)) == "Cn"


def runs(widths, nothing):
    """The table's runs: [first, last, thousandths] over code points that aren't two columns.

    `widths` maps a code point to its width in thousandths. A code point in `nothing` has no letter, so
    a run goes on across it and it never starts or ends one.
    """
    result = []
    for code in sorted(widths):
        if code in nothing:
            continue
        width = widths[code]
        if result and result[-1][2] == width and all(c in nothing for c in range(result[-1][1] + 1, code)):
            result[-1][1] = code
        else:
            result.append([code, code, width])
    return [run for run in result if run[2] != TWO]


def lines(table):
    return [f"{a:X} {w}" if a == b else f"{a:X}-{b:X} {w}" for a, b, w in table]


def header(environment):
    """Record the browser and fonts that actually made these widths."""
    return ["# J-Total widths, in thousandths of a digit", *[f"# {entry}" for entry in environment]]


def installed_fonts(packages):
    """List installed font packages, including those in the base image."""
    return [line.removesuffix(" installed") for line in packages.splitlines() if line.endswith(" installed")]


def font_faces(fonts):
    """J-Total's three Inconsolata files, from the folder `fonts`, each over its range."""
    return "".join(
        f'@font-face {{ font-family:"Inconsolata"; src:url("{(Path(fonts) / f"inconsolata-{name}.woff2").resolve().as_uri()}") format("woff2"); unicode-range:{span}; }}\n'
        for name, (_, span) in FONTS.items()
    )


def page_head(stack, fonts, lang=None, charset="utf-8"):
    """The top of a page that draws `tt` as J-Total does: its font stack at 40px, one line a row."""
    tag = f' lang="{lang}"' if lang else ""
    return (f'<!DOCTYPE html><html{tag}><head><meta charset="{charset}"><style>\n{font_faces(fonts)}'
            'tt { font-family:' + stack + '; font-size:40px; line-height:1; white-space:pre; }\ndiv { margin:0; }\n</style></head><body>')


def in_cp932(code):
    """True for a letter a Shift_JIS page can hold without a numeric reference."""
    try:
        chr(code).encode("cp932")
    except UnicodeEncodeError:
        return False
    return True


def written(code, as_entity=()):
    """`code` as J-Total's Shift_JIS page writes it: the letter itself, or a numeric reference for one it can't hold."""
    return chr(code) if in_cp932(code) and code not in as_entity else f"&#{code};"


def shift_jis_page(html):
    """The page as J-Total serves it: Shift_JIS, untagged. Every letter it can't hold is already a reference."""
    return html.encode("cp932")


def probe_rows(codes, as_entity=()):
    """One row for the base `ああ` and one for each code point between two of them."""
    return '<div><tt data-base="1">ああ</tt></div>' + "".join(
        f'<div><tt data-c="{c}">あ<span>{written(c, as_entity)}</span>あ</tt></div>' for c in codes
    )


# What a letter adds to a line: the width of `あXあ` less the width of `ああ`, in thousandths of a digit.
# The letter's own box is not enough: a mark that Chromium draws as part of the letter before it, such as
# U+FF9E after ｶ, has a box of no width but still moves the next letter on.
PROBE_JS = """() => {
  const probe = document.createElement('tt'); probe.textContent = '0'; document.body.appendChild(probe);
  const digit = probe.getBoundingClientRect().width;
  const base = document.querySelector('tt[data-base]').getBoundingClientRect().width;
  const out = {}, read = {};
  for (const tt of document.querySelectorAll('tt[data-c]')) {
    out[tt.dataset.c] = Math.round((tt.getBoundingClientRect().width - base) / digit * 1000);
    read[tt.dataset.c] = tt.querySelector('span').textContent.codePointAt(0);
  }
  return {digit, out, read};
}"""


def platform_fonts(cdp, selector):
    """The fonts the browser drew each node of `selector` with, in document order."""
    root = cdp.send("DOM.getDocument", {"depth": 0})["root"]["nodeId"]
    nodes = cdp.send("DOM.querySelectorAll", {"nodeId": root, "selector": selector})["nodeIds"]
    return [sorted({f["familyName"] for f in cdp.send("CSS.getPlatformFontsForNode", {"nodeId": node})["fonts"]}) for node in nodes]


def open_page(p, browser_name, lang):
    browser = launch(p, browser_name, lang)
    page = browser.new_page(viewport={"width": 1200, "height": 800}, locale=lang)
    cdp = page.context.new_cdp_session(page) if browser_name != "webkit" else None
    if cdp:
        cdp.send("DOM.enable")
        cdp.send("CSS.enable")
    return browser, page, cdp


def show(page, html, path):
    path.write_bytes(shift_jis_page(html))
    page.goto(path.as_uri())
    page.evaluate("document.fonts.ready")
    page.wait_for_timeout(100)


def measure(out, stack=STACK, fonts=Path("/fonts"), browser_name="chromium", lang=None, codes=None):
    """The width each letter adds to a line, in thousandths of a digit, and the fonts that drew it. Writes JSON to `out`."""
    from playwright.sync_api import sync_playwright

    head = page_head(stack, fonts, charset="shift_jis")
    codes = spans_codes() if codes is None else codes
    widths, used, misread = {}, {}, set()
    began = time.monotonic()
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser, page, cdp = open_page(p, browser_name, lang)
        html = Path(tmp) / "widths.html"
        digit = None

        def batch_of(batch, as_entity=()):
            nonlocal digit
            show(page, head + probe_rows(batch, as_entity) + "</body></html>", html)
            found = page.evaluate(PROBE_JS)
            digit = found["digit"]
            for c in batch:
                if found["read"][str(c)] != c:
                    misread.add(c)
                    continue
                widths[c] = found["out"][str(c)]
            if cdp:
                used.update((c, f) for c, f in zip(batch, platform_fonts(cdp, "tt[data-c] span")) if c in widths)

        for start in range(0, len(codes), BATCH):
            batch_of(codes[start:start + BATCH])
            if start // BATCH % 20 == 0:
                print(f"{start + BATCH} of {len(codes)} code points, {time.monotonic() - began:.0f} s", file=sys.stderr, flush=True)
        # A letter the browser's Shift_JIS reads as another one is written as a reference instead, as a page would have to.
        retry = sorted(misread)
        misread.clear()
        if retry:
            batch_of(retry, as_entity=set(retry))
        found = environment(browser, browser_name if "/" not in browser_name else Path(browser_name).stem, stack, fonts)
        found.append(f"Page Shift_JIS with no language; browser language {lang or 'as Playwright starts it'}")
        found.append(f"One digit is {digit} CSS px at 40px. A width is what the letter adds between two hiragana.")
        browser.close()
    if misread:
        raise SystemExit(f"read as other letters even as references: {sorted(misread)[:20]}")
    nothing = [c for c in codes if unassigned(c)]
    data = {"widths": {str(c): v for c, v in widths.items()}, "nothing": nothing, "environment": found,
            "fonts": {str(c): f for c, f in used.items()}, "stack": stack}
    Path(out).write_text(json.dumps(data), encoding="utf-8")
    print(f"{out}: {len(widths)} code points, {time.monotonic() - began:.0f} s", file=sys.stderr)


# Full-width brackets and stops, and what sits beside them in lyrics, for the trimming probe.
PUNCTUATION = "、。，．・：；！？「」『』（）［］｛｝〈〉《》【】〔〕“”‘’\u3000"
NEIGHBOURS = "あ字Ａa1 "


def trim_rows(marks=PUNCTUATION, neighbours=NEIGHBOURS):
    """Rows for each pair of marks, and each mark beside a letter, between two hiragana; and each mark at a line's start and end."""
    letters = marks + neighbours
    pairs = [(a, b) for a in letters for b in letters if a in marks or b in marks]
    rows = '<div><tt data-base="1">ああ</tt></div><div><tt data-one="1">あ</tt></div>'
    rows += "".join(f'<div><tt data-pair="{a}{b}">あ{a}{b}あ</tt></div>' for a, b in pairs)
    rows += "".join(f'<div><tt data-first="{m}">{m}あ</tt></div>' for m in marks)
    rows += "".join(f'<div><tt data-later="{m}">あ<br>{m}あ</tt></div>' for m in marks)
    rows += "".join(f'<div><tt data-last="{m}">あ{m}</tt></div>' for m in marks)
    return rows


# Each pair is what the two marks add between two hiragana. `first` is a mark at the start of the first line,
# `later` at the start of a line after a <br>, `last` at a line's end: each what it adds to `あ` there.
TRIM_JS = """() => {
  const probe = document.createElement('tt'); probe.textContent = '0'; document.body.appendChild(probe);
  const digit = probe.getBoundingClientRect().width;
  const width = (e) => e.getBoundingClientRect().width;
  const base = width(document.querySelector('tt[data-base]')), one = width(document.querySelector('tt[data-one]'));
  const k = (v) => Math.round(v / digit * 1000);
  const out = {pairs: {}, first: {}, later: {}, last: {}};
  for (const tt of document.querySelectorAll('tt[data-pair]')) out.pairs[tt.dataset.pair] = k(width(tt) - base);
  for (const tt of document.querySelectorAll('tt[data-first]')) out.first[tt.dataset.first] = k(width(tt) - one);
  for (const tt of document.querySelectorAll('tt[data-later]')) { const r = tt.getClientRects(); out.later[tt.dataset.later] = k(r[r.length - 1].width - one); }
  for (const tt of document.querySelectorAll('tt[data-last]')) out.last[tt.dataset.last] = k(width(tt) - one);
  return out;
}"""


def trims(out, stack=STACK, fonts=Path("/fonts"), browser_name="chromium", lang=None):
    """How wide full-width brackets and stops are beside each other and at a line's ends. Writes JSON to `out`."""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser, page, cdp = open_page(p, browser_name, lang)
        show(page, page_head(stack, fonts, charset="shift_jis") + trim_rows() + "</body></html>", Path(tmp) / "trims.html")
        found = page.evaluate(TRIM_JS)
        marks = dict(zip(PUNCTUATION, platform_fonts(cdp, "tt[data-last]"))) if cdp else {}
        environment_found = environment(browser, browser_name if "/" not in browser_name else Path(browser_name).stem, stack, fonts)
        browser.close()
    Path(out).write_text(json.dumps({**found, "fonts": marks, "environment": environment_found, "stack": stack}, ensure_ascii=False), encoding="utf-8")
    print(f"{out}: {len(found['pairs'])} pairs", file=sys.stderr)


def font_packages():
    """The font packages of a Debian or Ubuntu machine. Elsewhere, what the machine is, as its fonts come with it."""
    if shutil.which("dpkg-query"):
        packages = subprocess.check_output(['dpkg-query', '-W', '-f=${Package} ${Version} ${db:Status-Status}\\n', 'fonts-*'], text=True)
        return installed_fonts(packages)
    if shutil.which("sw_vers"):
        build = subprocess.check_output(["sw_vers", "-buildVersion"], text=True).strip()
        return [f"macOS {platform.mac_ver()[0]} ({build}) {platform.machine()}, its own fonts"]
    if platform.system() == "Windows":
        return [f"Windows {platform.version()} {platform.machine()}, its own fonts", *windows_fonts()]
    return [f"{platform.system()} {platform.release()}: no dpkg-query, so the font packages are not recorded"]


# The Japanese fonts a Windows reader's browser can draw the page with, by file.
WINDOWS_FONTS = ("msgothic.ttc", "meiryo.ttc", "YuGothR.ttc", "consola.ttf", "arial.ttf", "seguiemj.ttf")


def windows_fonts(folder=Path("C:/Windows/Fonts")):
    """Each font file of WINDOWS_FONTS, with each face it holds and that face's version, or that the file is missing."""
    found = []
    for name in WINDOWS_FONTS:
        path = folder / name
        if not path.exists():
            found.append(f"{name}: not on this machine")
            continue
        try:
            from fontTools.ttLib import TTCollection, TTFont
        except ImportError:
            found.append(f"{name}: present, SHA-256 {hashlib.sha256(path.read_bytes()).hexdigest()}")
            continue
        faces = TTCollection(str(path)).fonts if name.lower().endswith(".ttc") else [TTFont(str(path))]
        found.append(f"{name}: " + "; ".join(f"{face['name'].getDebugName(4)} {face['name'].getDebugName(5)}" for face in faces))
    return found


def launch(p, browser, lang=None):
    """Starts `browser`: chromium or webkit as Playwright ships them, or a Chromium-based browser at that path.

    `lang` also sets the browser's own language, which picks the fonts Chromium draws a generic family in.
    """
    args = [f"--lang={lang}"] if lang and browser != "webkit" else []
    if browser in ("chromium", "webkit"):
        return getattr(p, browser).launch(headless=True, args=args)
    if not Path(browser).exists():
        raise SystemExit(f"{browser}: no such browser on this machine")
    return p.chromium.launch(headless=True, executable_path=browser, args=args)


def inconsolata_hashes(fonts):
    return 'Inconsolata v37 (Google Fonts), SHA-256: ' + ', '.join(
        f'{name}={hashlib.sha256((Path(fonts) / f"inconsolata-{name}.woff2").read_bytes()).hexdigest()}' for name in FONTS
    )


def environment(browser, name, stack, fonts):
    """What made a measurement: the stack, the browser, Playwright, Unicode, and the fonts."""
    return [
        f"Font stack {stack}",
        f"{name.capitalize()} {browser.version}",
        f"Playwright {metadata.version('playwright')}",
        f"Unicode {unicodedata.unidata_version}",
        *font_packages(),
        inconsolata_hashes(fonts),
        *([aliases(ALIASES.read_text(encoding="utf-8"))] if ALIASES.exists() else []),
    ]


def aliases(config):
    """The font names fontconfig answers with another font, as `config` (its local.conf) sets them."""
    pairs = re.findall(r"<alias[^>]*>\s*<family>([^<]+)</family>\s*<prefer>\s*<family>([^<]+)</family>", config)
    return "Fontconfig aliases: " + "; ".join(f"{name} is {font}" for name, font in pairs)


def cache():
    mnt = Path("/mnt/data/agent-cache")
    return (mnt if mnt.is_dir() else Path.home() / ".cache/agent-cache") / "crosstune-jtotal-widths"


def container_fonts():
    """Downloads J-Total's three Inconsolata files once, and gives back their folder."""
    fonts = cache() / "fonts"
    fonts.mkdir(parents=True, exist_ok=True)
    for name, (url, _) in FONTS.items():
        font = fonts / f"inconsolata-{name}.woff2"
        if not font.exists():
            font.write_bytes(urllib.request.urlopen(url, timeout=60).read())
    return fonts


def container():
    """Downloads Inconsolata once, builds the image, and gives back the folder that holds the fonts' work files."""
    fonts = container_fonts()
    subprocess.run(["podman", "build", "-t", IMAGE, "-f", str(HERE / "Containerfile"), str(HERE)], check=True)
    return fonts.parent


def run_in_container(work, script):
    """Runs `python3 SCRIPT...` in the container, with `work` at /work, the fonts at /fonts and this folder at /script."""
    fonts = container() / "fonts"
    subprocess.run(
        ["podman", "run", "--rm", "-v", f"{work}:/work", "-v", f"{fonts}:/fonts:ro", "-v", f"{HERE}:/script:ro",
         "-e", "PYTHONPATH=/script", IMAGE, "python3", *script],
        check=True,
    )


def default_fonts():
    """J-Total's Inconsolata files: at /fonts in the container, downloaded once anywhere else."""
    return Path("/fonts") if Path("/fonts").is_dir() else container_fonts()


# The files a measuring run writes into its folder, and the stack each is for.
RUNS = {"main": STACK, "mono": MONO_STACK}


def in_container(work):
    """Measures both stacks and the trimming in the table's container, into `work`."""
    for name in RUNS:
        mono = ["--mono"] if name == "mono" else []
        run_in_container(work, ["/script/jtotal_widths.py", "measure", f"/work/{name}.json", *mono])
        run_in_container(work, ["/script/jtotal_widths.py", "trims", f"/work/{name}-trims.json", *mono])


def table_text(raw, source):
    """The table from one measuring run: its header, then each run that isn't two columns."""
    widths = {int(c): w for c, w in raw["widths"].items()}
    return "\n".join(header([source, *raw["environment"]]) + lines(runs(widths, set(raw["nothing"])))) + "\n"


def tables(directory):
    """Both tables, as the measurement in `directory` makes them. Its SOURCE.txt says where it was measured."""
    source = (directory / "SOURCE.txt").read_text(encoding="utf-8").strip()
    return [(table, table_text(json.loads((directory / f"{name}.json").read_text(encoding="utf-8")), source))
            for table, name in ((TABLE, "main"), (MONO_TABLE, "mono"))]


def differences(a, b):
    """Code points measured at another width in `b` than in `a`: (code, width in a, width in b)."""
    wa, wb = a["widths"], b["widths"]
    return [(int(c), wa[c], wb[c]) for c in sorted(wa, key=int) if c in wb and wa[c] != wb[c]]


def diff_main(a_path, b_path, show):
    a, b = (json.loads(Path(x).read_text(encoding="utf-8")) for x in (a_path, b_path))
    found = differences(a, b)
    shift_jis = [d for d in found if in_cp932(d[0])]
    print(f"{len(found)} code points differ, {len(shift_jis)} of them in Windows-31J")
    for code, x, y in (shift_jis + [d for d in found if not in_cp932(d[0])])[:show]:
        fonts = lambda m: "/".join(m.get("fonts", {}).get(str(code), [])) or "?"
        print(f"U+{code:04X} {chr(code)!r} {x / 1000:.3f} ({fonts(a)}) -> {y / 1000:.3f} ({fonts(b)})")
    for kind in ("pairs", "first", "later", "last"):
        ta, tb = (json.loads(Path(x).with_name(Path(x).stem + "-trims.json").read_text(encoding="utf-8")) if Path(x).with_name(Path(x).stem + "-trims.json").exists() else None for x in (a_path, b_path))
        if ta is None or tb is None:
            break
        moved = [k for k in ta[kind] if k in tb[kind] and ta[kind][k] != tb[kind][k]]
        print(f"{kind}: {len(moved)} of {len(ta[kind])} differ" + (": " + " ".join(f"{k!r} {ta[kind][k]}->{tb[kind][k]}" for k in moved[:show]) if moved else ""))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("measure", "trims", "container", "generate", "check", "diff"))
    parser.add_argument("paths", nargs="*")
    parser.add_argument("--mono", action="store_true")
    parser.add_argument("--browser", default="chromium")
    parser.add_argument("--lang")
    parser.add_argument("--fonts", type=Path)
    parser.add_argument("--show", type=int, default=50)
    parser.add_argument("--stack", help="another font stack than the page's, such as one naming MS PGothic")
    parser.add_argument("--cp932", action="store_true", help="only the letters a Shift_JIS page holds")
    args = parser.parse_args(argv)
    stack = args.stack or (MONO_STACK if args.mono else STACK)
    fonts = args.fonts or default_fonts()
    if args.command == "measure":
        codes = [c for c in spans_codes() if in_cp932(c)] if args.cp932 else None
        measure(args.paths[0], stack, fonts, args.browser, args.lang, codes)
        return 0
    if args.command == "trims":
        trims(args.paths[0], stack, fonts, args.browser, args.lang)
        return 0
    if args.command == "container":
        work = Path(args.paths[0]).resolve()
        work.mkdir(parents=True, exist_ok=True)
        in_container(work)
        (work / "SOURCE.txt").write_text("Measured in the Containerfile's container\n", encoding="utf-8")
        return 0
    if args.command == "diff":
        diff_main(args.paths[0], args.paths[1], args.show)
        return 0
    made = tables(Path(args.paths[0]))
    if args.command == "generate":
        for table, text in made:
            table.write_text(text, encoding="utf-8")
            print(f"{table.relative_to(REPO)}: {len(text.splitlines())} lines")
        return 0
    if all(table.read_text(encoding="utf-8") == text for table, text in made):
        print("the tables are what that measurement says")
        return 0
    print("the tables differ from that measurement: run `generate` and look at the diff", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
