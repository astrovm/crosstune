#!/usr/bin/env python3
"""Where J-Total's page puts each chord, against where the app puts it, on crawled sheets.

  python3 scripts/jtotal-widths/compare_sheets.py crawl DIR URLS.txt [--limit N]
  python3 scripts/jtotal-widths/compare_sheets.py browser DIR            # DIR/browser.json
  python3 scripts/jtotal-widths/compare_sheets.py draw DIR --fonts FONTS [--browser B] [--lang ja] [--out NAME.json]
  python3 scripts/jtotal-widths/compare_sheets.py moves A.json B.json    # chords that sit over another letter in B
  python3 scripts/jtotal-widths/compare_sheets.py app DIR TAG [--old]    # DIR/app-TAG.jsonl
  python3 scripts/jtotal-widths/compare_sheets.py compare DIR TAG [--show N]

`crawl` saves the pages in URLS.txt (one address a line) under DIR/sheets, one request a second at most.
Pages are another person's work, so DIR belongs in the agent cache and never in Git.
`browser` draws every page in Chromium, in the container of the width table, with the page's own CSS.
It reads where each chord link starts and where each letter of the line under it starts.
`app` runs `ChordSheets.fromJTotal`, the code of this checkout, on the same pages with a throwaway
test that it puts in the app's test folder and takes out again. `--old` leaves out the font stack
the page names and reads the page as Java's Shift_JIS, for a checkout from before the app did either.
`draw` is what `browser` runs in the container. Run on another machine, it draws the pages with that
machine's browser and fonts, such as a Mac's, and writes NAME.environment.txt beside the drawing.
`moves` puts two drawings side by side: how many chords sit over another letter, by how many columns,
which code points before them are drawn at another width, and how many chords start where a letter
starts in each. An author lines a chord up with a letter on their own screen, so the drawing that puts
more chords on a letter start is nearer to the one the sheets were written on.
`compare` puts the two side by side: a chord is right when the app's letter is the one the browser's
chord starts nearest to. Lines that the app and the browser read as other words or other chords
are counted on their own, as nothing says which letter would be right there.
"""
import argparse
import difflib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
REPO = HERE.parents[1] if len(HERE.parents) > 1 else HERE  # in the container the script sits at /script
TEST = REPO / "app/src/test/java/com/astrovm/crosstune/CrawlDumpTest.kt"
TEMPLATE = HERE / "CrawlDump.kt.template"
STYLESHEET = "https://music.j-total.net/ssi_dat/dat_st.css"
CHARSET = "cp932"  # what browsers call Shift_JIS
MONO = "Inconsolata, monospace"
EPSILON = 0.05  # CSS pixels: letters this close start in the same place

# The page's chords: the links to a picture of the chord, with any bass written after the link.
JS = r"""() => {
 const comment = (re) => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_COMMENT); let c; while ((c = w.nextNode())) if (re.test(c.data)) return c; return null; };
 const first = comment(/^HPSTART/);
 // The sheet is drawn in one piece, as a wide window draws it: nothing wraps but at a <br>.
 for (let e = first && first.parentElement; e && e !== document.body; e = e.parentElement) {
  for (const [k, v] of [['width', 'max-content'], ['max-width', 'none'], ['float', 'none']]) e.style.setProperty(k, v, 'important');
 }
 const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ALL);
 const isChord = (e) => e.nodeType == 1 && e.tagName == 'A' && /cd\//.test(e.getAttribute('href') || '');
 const lines = []; let cur = { chars: [], chords: [] }, inside = false, font = null, pending = null, n;
 while ((n = walker.nextNode())) {
  if (n.nodeType == 8) { if (/^HPSTART/.test(n.data)) inside = true; else if (inside && /^HPEND/.test(n.data)) break; continue; }
  if (!inside) continue;
  if (n.nodeType == 1) {
   if (n.tagName == 'BR') {
    lines.push(cur);
    // A chord link with a <br> before its name is drawn after it: the name starts the next line.
    cur = { chars: [], chords: pending && pending.br === n ? [pending.chord] : [] };
    if (pending && pending.br === n) pending = null;
   } else if (isChord(n)) {
    let bass = null;
    const next = n.nextSibling;
    if (next && next.nodeType == 3) { const m = /^on([A-G][#b]?)/.exec(next.data); if (m) bass = m[1]; }
    if (font === null) font = getComputedStyle(n).fontFamily;
    // A <br> inside the link ends a line: the name is drawn on the line it starts on, so after the breaks before it.
    const inner = document.createTreeWalker(n, NodeFilter.SHOW_TEXT);
    let letters = null, t;
    while (!letters && (t = inner.nextNode())) if (/\S/.test(t.data)) letters = t;
    const range = document.createRange();
    range.selectNodeContents(letters || n);
    const boxes = [...range.getClientRects()].filter((r) => r.width > 0);
    const chord = { name: n.textContent, bass, left: (boxes[0] || n.getBoundingClientRect()).left };
    const before = letters ? [...n.querySelectorAll('br')].filter((br) => br.compareDocumentPosition(letters) & Node.DOCUMENT_POSITION_FOLLOWING) : [];
    if (before.length) pending = { chord, br: before[before.length - 1] }; else cur.chords.push(chord);
   }
   continue;
  }
  if (n.parentElement && n.parentElement.closest('a[href*="cd/"]')) continue;
  const text = n.data;
  for (let i = 0; i < text.length;) {
   const len = text.codePointAt(i) > 0xFFFF ? 2 : 1, range = document.createRange();
   range.setStart(n, i); range.setEnd(n, i + len);
   const rect = range.getClientRects()[0], space = /[ \t\r\n]/.test(text[i]);
   if (rect && (rect.width > 0 || !space)) cur.chars.push({ c: text.substr(i, len), left: rect.left, right: rect.right });
   else if (!space) { const box = range.getBoundingClientRect(); cur.chars.push({ c: text.substr(i, len), left: box.left, right: box.right }); }
   i += len;
  }
 }
 lines.push(cur);
 // The left edge of a line: where an empty span at the very start of the sheet sits.
 const probe = document.createElement('span');
 first.parentNode.insertBefore(probe, first.nextSibling);
 return { x0: probe.getBoundingClientRect().left, font, lines };
}"""


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def chord_name(chord):
    """The name the app gives a chord: the link's text, and "/" and the bass written after the link."""
    name = chord["name"].strip()
    return f"{name}/{chord['bass']}" if chord["bass"] else name


def clean(page):
    """The page without chord links that name nothing: they draw no chord."""
    lines = [{**line, "chords": [c for c in line["chords"] if c["name"].strip()]} for line in page["lines"]]
    return {**page, "lines": [line for line in lines if "".join(c["c"] for c in line["chars"]).strip(" ") or line["chords"]]}


def letter_at(chars, x0, left):
    """The index, in UTF-16 units, of the letter that starts nearest to `left`. A tie goes to the later one."""
    starts, index = [], 0
    for char in chars:
        starts.append((char["left"], index))
        index += utf16_length(char["c"])
    # Past the last letter is a place too.
    starts.append((chars[-1]["right"], index) if chars else (x0, 0))
    best, best_start, best_distance = 0, 0.0, float("inf")
    for start, index in starts:
        distance = abs(start - left)
        if distance < best_distance - EPSILON or (abs(distance - best_distance) <= EPSILON and start > best_start + EPSILON):
            best, best_start, best_distance = index, start, distance
    return best


def expected(page):
    """What the browser draws: the chord lines, each the one above the words under it."""
    lines, result, i = clean(page)["lines"], [], 0
    while i < len(lines):
        line = lines[i]
        i += 1
        if not line["chords"]:
            continue
        words = lines[i] if i < len(lines) and not lines[i]["chords"] else None
        if words is not None:
            i += 1
        chars = words["chars"] if words else []
        text = "".join(c["c"] for c in chars).strip(" ")
        result.append({"text": text, "chords": [[letter_at(chars, page["x0"], c["left"]), chord_name(c)] for c in line["chords"]]})
    return result


def key(line):
    return line["text"], tuple(c[1] for c in line["chords"])


def compare_page(page, got):
    """Counts of one page: chords on the right letter, on another one, on lines that differ, and so on."""
    counts, wrong = Counter(), []
    drawn = expected(page)
    got = [line for line in got if line["chords"]]
    matcher = difflib.SequenceMatcher(None, [key(line) for line in drawn], [key(line) for line in got], autojunk=False)
    matched = set()
    for a, b, size in matcher.get_matching_blocks():
        for k in range(size):
            matched.add(a + k)
            counts["chord lines compared"] += 1
            for browser, app in zip(drawn[a + k]["chords"], got[b + k]["chords"]):
                counts["chords compared"] += 1
                if browser[0] != app[0]:
                    counts["chords on another letter"] += 1
                    wrong.append((drawn[a + k]["text"], browser, app))
    for k, line in enumerate(drawn):
        if k not in matched:
            counts["chord lines the app reads as other words or chords"] += 1
            counts["chords on those lines"] += len(line["chords"])
    counts["app chord lines the browser does not draw"] += len(got) - len(matched)
    return counts, wrong


def compare(truth, app):
    total, wrong_pages, examples = Counter(), 0, []
    for page in truth:
        got = app.get(page["url"])
        if got is None:
            total["sheets the app did not answer"] += 1
            continue
        counts, wrong = compare_page(page, got["lines"])
        total.update(counts)
        total["sheets compared"] += 1
        mono = page["font"] == MONO
        total["sheets in the monospace stack"] += mono
        total["sheets whose font stack the app misreads"] += "mono" in got and got["mono"] != mono
        differs = bool(wrong) or counts["chord lines the app reads as other words or chords"] or counts["app chord lines the browser does not draw"]
        wrong_pages += bool(wrong)
        total["sheets with a chord on another letter"] += bool(wrong)
        total["sheets with any difference"] += bool(differs)
        examples += [(page["url"], *w) for w in wrong]
    return total, examples


def read_app(path):
    return {row["url"]: row for row in map(json.loads, Path(path).read_text(encoding="utf-8").splitlines())}


# The steps that run on the host.

def crawl(directory, urls, limit):
    (directory / "sheets").mkdir(parents=True, exist_ok=True)
    meta, last, missing = [], 0.0, 0
    for number, url in enumerate(urls[:limit]):
        out = directory / "sheets" / f"{number:04}.html"
        if not out.exists():
            time.sleep(max(0.0, 1.1 - (time.monotonic() - last)))
            last = time.monotonic()
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; research crawler)"})
            try:
                out.write_bytes(urllib.request.urlopen(request, timeout=35).read())
            except urllib.error.HTTPError as error:
                # A guessed address that isn't a sheet is skipped, not a reason to stop the crawl.
                print(f"{url}: HTTP {error.code}", file=sys.stderr)
                missing += 1
                continue
        meta.append({"file": out.name, "url": url})
    sheet = directory / "dat_st.css"
    if not sheet.exists():
        sheet.write_bytes(urllib.request.urlopen(STYLESHEET, timeout=35).read())
    (directory / "urls.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(meta)} sheets in {directory}" + (f", {missing} addresses missing" if missing else ""))


def browser_in_container(directory):
    from jtotal_widths import run_in_container
    run_in_container(directory, ["/script/compare_sheets.py", "draw", "/work", "--fonts", "/fonts"])


# What `--old` takes out of the template, each with what goes in its place: the font stack, and Java's Windows-31J.
OLD = (
    ("            val mono = ChordSheets.jTotalMonospace(page)\n", ""),
    ("unescape(sheet), mono)", "unescape(sheet))"),
    (' + """,\"mono\":$mono"""', ""),
    ('"windows-31j"', '"Shift_JIS"'),
)


def old_template(source):
    """The dump test as a checkout from before the app read a page's font stack and Windows-31J can run it."""
    for with_stack, without in OLD:
        if with_stack not in source:
            raise ValueError(f"the template no longer has {with_stack!r}")
        source = source.replace(with_stack, without)
    return source


def app_run(directory, tag, old):
    """Runs the code of this checkout on the crawled pages, in the test box."""
    build = REPO / "app/build/crawl"
    shutil.rmtree(build, ignore_errors=True)
    shutil.copytree(directory / "sheets", build / "sheets")
    rows = json.loads((directory / "urls.json").read_text(encoding="utf-8"))
    (build / "urls.tsv").write_text("".join(f"{row['file']}\t{row['url']}\n" for row in rows), encoding="utf-8")
    (build / "tag.txt").write_text(tag, encoding="utf-8")
    source = TEMPLATE.read_text(encoding="utf-8")
    if old:
        source = old_template(source)
    # The sheets sit outside Gradle's inputs. A second replay writes the same test source, and Gradle
    # then skips the test, so the stamp makes this run a new source file.
    TEST.write_text(source + f"\n// replay {tag} {time.time_ns()}\n", encoding="utf-8")
    try:
        subprocess.run(
            ["nice", "-n", "10", str(REPO / "scripts/gradle-box/gradle-box.sh"), "--max-workers=2", ":app:testDebugUnitTest",
             "--tests", "com.astrovm.crosstune.CrawlDumpTest"],
            check=True, cwd=REPO,
        )
    finally:
        TEST.unlink(missing_ok=True)
    shutil.copy(build / f"app-{tag}.jsonl", directory / f"app-{tag}.jsonl")
    shutil.rmtree(build, ignore_errors=True)


# The step that runs in the container.

def draw(directory, fonts=Path("/fonts"), browser_name="chromium", lang=None, out="browser.json"):
    """Draws every page in `browser_name` on this machine, with its fonts, and writes where the letters are to `out`."""
    from playwright.sync_api import sync_playwright
    from jtotal_widths import FONTS, environment, open_page

    css = (directory / "dat_st.css").read_bytes().decode(CHARSET, "replace")
    faces = "".join(
        f'@font-face{{font-family:Inconsolata;src:url({(fonts / f"inconsolata-{n}.woff2").resolve().as_uri()});unicode-range:{s}}}'
        for n, (_, s) in FONTS.items()
    )
    pages = []
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser, page, _ = open_page(p, browser_name, lang, Path(tmp) / "profile", viewport=(1280, 900), scale=4)
        page.route("**/*", lambda r: r.continue_() if r.request.url.startswith("file:") else r.abort())
        html = Path(tmp) / "sheet.html"
        for item in json.loads((directory / "urls.json").read_text(encoding="utf-8")):
            raw = (directory / "sheets" / item["file"]).read_bytes().decode(CHARSET, "replace")
            if "<!--HPSTART-->" not in raw:
                continue
            text = re.sub(r'<link href="/ssi_dat/dat_st.css[^>]*>', lambda _: "<style>" + css + "</style>", raw)
            text = re.sub(r"(?is)<script\b.*?</script[^>]*>", "", text)
            text = text.replace("</head>", "<style>" + faces + " *{font-size:40px !important}</style></head>", 1)
            # Served as J-Total serves it, in Shift_JIS. A letter the decoder could not read stays the replacement mark.
            html.write_bytes(text.encode(CHARSET, "xmlcharrefreplace"))
            page.goto(html.as_uri())
            page.evaluate("document.fonts.ready")
            pages.append({**page.evaluate(JS), "url": item["url"]})
        found = environment(browser, browser_name if "/" not in browser_name else Path(browser_name).stem, "the page's own", fonts)
        browser.close()
    (directory / out).write_text(json.dumps(pages, ensure_ascii=False), encoding="utf-8")
    (directory / out).with_suffix(".environment.txt").write_text("\n".join(found + [f"Browser language {lang or 'as Playwright starts it'}"]) + "\n", encoding="utf-8")
    print(f"{len(pages)} sheets drawn")


def drawn_lines(page):
    """The chord lines of a drawn page, keyed by their words and chords, each with where its chords and letters are."""
    lines, result, i = clean(page)["lines"], [], 0
    while i < len(lines):
        line = lines[i]
        i += 1
        if not line["chords"]:
            continue
        words = lines[i] if i < len(lines) and not lines[i]["chords"] else None
        if words is not None:
            i += 1
        chars = words["chars"] if words else []
        text = "".join(c["c"] for c in chars).strip(" ")
        result.append({"key": (text, tuple(chord_name(c) for c in line["chords"])), "chars": chars, "chords": line["chords"], "x0": page["x0"]})
    return result


def starts(chars):
    """Where each letter starts, by its index in UTF-16 units, and where the line ends."""
    found, index = {}, 0
    for char in chars:
        found[index] = char["left"]
        index += utf16_length(char["c"])
    if chars:
        found[index] = chars[-1]["right"]
    return found


def on_a_start(chars, left):
    """True when a chord starts where a letter of the words under it starts: the author lined it up there."""
    return any(abs(start - left) <= EPSILON for start in starts(chars).values())


def moves(a_pages, b_pages, digit=20.0):
    """Chords that sit over another letter in drawing B than in drawing A, on sheets both drew.

    Gives the counts, a Counter of how many columns of A each move is, and a Counter of the code points
    before a moved chord that B draws at another width than A.
    """
    by_url = {page["url"]: page for page in b_pages}
    counts, shifts, culprits = Counter(), Counter(), Counter()
    for a_page in a_pages:
        b_page = by_url.get(a_page["url"])
        if b_page is None:
            counts["sheets only A drew"] += 1
            continue
        stack = "mono" if a_page["font"] == MONO else "main"
        counts[f"sheets ({stack} stack)"] += 1
        a_lines, b_lines = drawn_lines(a_page), drawn_lines(b_page)
        matcher = difflib.SequenceMatcher(None, [x["key"] for x in a_lines], [x["key"] for x in b_lines], autojunk=False)
        moved_here = False
        for i, j, size in matcher.get_matching_blocks():
            for k in range(size):
                a_line, b_line = a_lines[i + k], b_lines[j + k]
                a_starts = starts(a_line["chars"])
                for a_chord, b_chord in zip(a_line["chords"], b_line["chords"]):
                    counts[f"chords ({stack} stack)"] += 1
                    counts[f"chords on a letter start in A ({stack} stack)"] += on_a_start(a_line["chars"], a_chord["left"])
                    counts[f"chords on a letter start in B ({stack} stack)"] += on_a_start(b_line["chars"], b_chord["left"])
                    a_at = letter_at(a_line["chars"], a_line["x0"], a_chord["left"])
                    b_at = letter_at(b_line["chars"], b_line["x0"], b_chord["left"])
                    if a_at == b_at:
                        continue
                    moved_here = True
                    counts[f"chords on another letter ({stack} stack)"] += 1
                    shifts[(stack, round((a_starts[b_at] - a_starts[a_at]) / digit, 1))] += 1
                    # A space drawn at no width is left out of a drawing, so the letters pair up only when both kept the same ones.
                    paired = [x["c"] for x in a_line["chars"]] == [x["c"] for x in b_line["chars"]]
                    for a_char, b_char in zip(a_line["chars"], b_line["chars"]) if paired else ():
                        if a_char["left"] >= a_chord["left"] - EPSILON:
                            break
                        if abs((a_char["right"] - a_char["left"]) - (b_char["right"] - b_char["left"])) > EPSILON:
                            culprits[(stack, ord(a_char["c"][0]))] += 1
        counts[f"sheets with a chord on another letter ({stack} stack)"] += moved_here
    return counts, shifts, culprits


def moves_main(a_path, b_path, show):
    counts, shifts, culprits = moves(*(json.loads(Path(path).read_text(encoding="utf-8")) for path in (a_path, b_path)))
    for name, value in sorted(counts.items()):
        print(f"{value:7d}  {name}")
    print("\nHow far a moved chord goes, in columns of A (stack, columns, chords):")
    for (stack, columns), value in sorted(shifts.items()):
        print(f"  {stack:4} {columns:+5.1f} {value}")
    print("\nCode points before a moved chord that B draws at another width (stack, code point, chords):")
    for (stack, code), value in culprits.most_common(show or None):
        print(f"  {stack:4} U+{code:04X} {value}")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("crawl", "browser", "draw", "app", "compare", "moves"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("extra", nargs="?")
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--old", action="store_true")
    parser.add_argument("--show", type=int, default=0)
    parser.add_argument("--fonts", type=Path, default=Path("/fonts"))
    parser.add_argument("--browser", default="chromium")
    parser.add_argument("--lang")
    parser.add_argument("--out", default="browser.json")
    args = parser.parse_args(argv)
    directory = args.directory.resolve()
    if args.command == "crawl":
        crawl(directory, Path(args.extra).read_text(encoding="utf-8").split(), args.limit)
    elif args.command == "browser":
        browser_in_container(directory)
    elif args.command == "draw":
        draw(directory, args.fonts, args.browser, args.lang, args.out)
    elif args.command == "app":
        app_run(directory, args.extra, args.old)
    elif args.command == "moves":
        moves_main(directory, Path(args.extra), args.show)
    else:
        total, examples = compare(json.loads((directory / "browser.json").read_text(encoding="utf-8")), read_app(directory / f"app-{args.extra}.jsonl"))
        for name, value in total.items():
            print(f"{value:7d}  {name}")
        for example in examples[:args.show]:
            print(example)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
