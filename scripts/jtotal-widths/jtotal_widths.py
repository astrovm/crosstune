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


def page_head(stack, fonts, lang=None):
    """The top of a page that draws `tt` as J-Total does: its font stack at 40px, one line a row."""
    tag = f' lang="{lang}"' if lang else ""
    return (f'<!DOCTYPE html><html{tag}><head><meta charset="utf-8"><style>\n{font_faces(fonts)}'
            'tt { font-family:' + stack + '; font-size:40px; line-height:1; white-space:pre; }\ndiv { margin:0; }\n</style></head><body>')


def font_packages():
    """The font packages of a Debian or Ubuntu machine. Elsewhere, what the machine is, as its fonts come with it."""
    if shutil.which("dpkg-query"):
        packages = subprocess.check_output(['dpkg-query', '-W', '-f=${Package} ${Version} ${db:Status-Status}\\n', 'fonts-*'], text=True)
        return installed_fonts(packages)
    if shutil.which("sw_vers"):
        build = subprocess.check_output(["sw_vers", "-buildVersion"], text=True).strip()
        return [f"macOS {platform.mac_ver()[0]} ({build}) {platform.machine()}, its own fonts"]
    return [f"{platform.system()} {platform.release()}: no dpkg-query, so the font packages are not recorded"]


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


def measure(out, stack=STACK, fonts=Path("/fonts")):
    """Inside the container: the width of every letter, in thousandths of a digit. Writes JSON to `out`."""
    from playwright.sync_api import sync_playwright

    head = page_head(stack, fonts)
    codes = spans_codes()
    widths = {}
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser = launch(p, "chromium")
        page = browser.new_page(viewport={"width": 1200, "height": 800})
        html = Path(tmp) / "widths.html"
        for start in range(0, len(codes), BATCH):
            rows = "".join(f'<div><tt data-c="{c}">あ<s>{chr(c)}</s>あ</tt></div>' for c in codes[start:start + BATCH])
            html.write_text(head + rows + "</body></html>", encoding="utf-8")
            page.goto(html.as_uri())
            page.evaluate("document.fonts.ready")
            page.wait_for_timeout(300)
            widths.update(page.evaluate("""() => {
              const probe = document.createElement('tt'); probe.textContent = '0'; document.body.appendChild(probe);
              const digit = probe.getBoundingClientRect().width;
              const out = {};
              for (const tt of document.querySelectorAll('tt[data-c]')) {
                out[tt.dataset.c] = Math.round(tt.querySelector('s').getBoundingClientRect().width / digit * 1000);
              }
              return out;
            }"""))
        found = environment(browser, "chromium", stack, fonts)
        browser.close()
    nothing = [c for c in codes if unassigned(c)]
    Path(out).write_text(json.dumps({"widths": widths, "nothing": nothing, "environment": found}), encoding="utf-8")


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


def in_container(mono=False):
    """Measures in the container and gives back the table text it makes."""
    work = cache()
    run_in_container(work, ["/script/jtotal_widths.py", "measure", "/work/raw.json", *(["--mono"] if mono else [])])
    raw = json.loads((work / "raw.json").read_text(encoding="utf-8"))
    widths = {int(c): w for c, w in raw["widths"].items()}
    return "\n".join(header(raw["environment"]) + lines(runs(widths, set(raw["nothing"])))) + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("generate", "check", "measure"))
    parser.add_argument("out", nargs="?")
    parser.add_argument("--mono", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "measure":
        measure(args.out, MONO_STACK if args.mono else STACK)
        return 0
    tables = [(TABLE, in_container()), (MONO_TABLE, in_container(mono=True))]
    if args.command == "generate":
        for table, text in tables:
            table.parent.mkdir(parents=True, exist_ok=True)
            table.write_text(text, encoding="utf-8")
            print(f"{table.relative_to(REPO)}: {len(text.splitlines())} lines")
        return 0
    if all(table.read_text(encoding="utf-8") == text for table, text in tables):
        print("the table is what the browser measures")
        return 0
    print("the table differs from the browser: run `generate` and look at the diff", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
