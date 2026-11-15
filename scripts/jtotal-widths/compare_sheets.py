#!/usr/bin/env python3
"""Where J-Total's page puts each chord, against where the app puts it, on crawled sheets.

  python3 scripts/jtotal-widths/compare_sheets.py crawl DIR URLS.txt [--limit N]
  python3 scripts/jtotal-widths/compare_sheets.py browser DIR            # DIR/browser.json
  python3 scripts/jtotal-widths/compare_sheets.py draw DIR --fonts FONTS [--browser B] [--lang ja] [--out NAME.json] [--size PX|page] [--scale S]
  python3 scripts/jtotal-widths/compare_sheets.py moves A.json B.json    # chords that sit over another letter in B
  python3 scripts/jtotal-widths/compare_sheets.py app DIR TAG [--old]    # DIR/app-TAG.jsonl
  python3 scripts/jtotal-widths/compare_sheets.py positions DRAWING.json OUT.json   # the chords, with no words
  python3 scripts/jtotal-widths/compare_sheets.py compare DIR TAG [--truth DRAWING.json] [--show N]
  python3 scripts/jtotal-widths/compare_sheets.py against DIR BEFORE AFTER [--truth DRAWING.json] [--show N]

`crawl` saves the pages in URLS.txt (one address a line) under DIR/sheets, one request a second at most.
A page the site doesn't send is named and skipped; the wait doubles after each failure, and five in a
row end the crawl, with what it has.
Pages are another person's work, so DIR belongs in the agent cache and never in Git.
`browser` draws every page in Chromium, in the container of the width table, with the page's own CSS.
It reads where each chord link starts and where each letter of the line under it starts.
`app` runs `ChordSheets.fromJTotal`, the code of this checkout, on the same pages with a throwaway
test that it puts in the app's test folder and takes out again. `--old` leaves out the font stack
the page names and reads the page as Java's Shift_JIS, for a checkout from before the app did either.
`draw` is what `browser` runs in the container. Run on another machine, it draws the pages with that
machine's browser and fonts, such as a Mac's, and writes NAME.environment.txt beside the drawing.
It loads files on this machine only, with the page's links read against J-Total's address, and a page
that fails to draw is named in NAME.environment.txt while the others are still drawn.
It draws every letter at 40px on a screen of 4 pixels to a CSS pixel, which places each most exactly.
`--size page --scale 1.25` draws them as a reader sees them: at the page's own 15px, on a screen
Windows scales by 125%.
`moves` puts two drawings side by side: how many chords sit over another letter, by how many columns,
which code points before them are drawn at another width, and how many chords start where a letter
starts in each. An author lines a chord up with a letter on their own screen, so the drawing that puts
more chords on a letter start is nearer to the one the sheets were written on.
`positions` keeps only the letter each chord of a drawing is on, and for each line a hash of its words
and chord names together, so a drawing made elsewhere, such as on the Windows workflow's runner, can
come back with neither the words nor the chords.
`compare` puts the two side by side, against DIR/browser.json or the drawing or positions in --truth: a chord is right when the app's letter is the one the browser's
chord starts nearest to. Lines that the app and the browser read as other words or other chords
are counted on their own, as nothing says which letter would be right there.
`against` puts two apps' runs, such as main's and a branch's, side by side against one drawing, chord by chord:
how many chords each has right, how many are right in BEFORE and off in AFTER, and each of those, with --show.
"""
import argparse
import difflib
import hashlib
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


def hidden(line):
    """A line's words and chord names as one hash: enough to match the app's line to it, and neither of them."""
    return hashlib.sha256(json.dumps(key(line), ensure_ascii=False).encode("utf-8")).hexdigest()[:16]


def positions(drawing):
    """Each page's chord lines as `expected` gives them, each as `hidden` and the letters its chords are on."""
    return [{"url": page["url"], "font": page["font"], "hidden": True,
             "lines": [{"key": hidden(line), "at": [chord[0] for chord in line["chords"]]} for line in expected(page)]}
            for page in drawing]


def matched_lines(page, got):
    """The app's chord lines, the letters the drawing's chords are on, line by line, and each (drawing's line, app's
    line) that read as the same words and chords.

    `page` is a drawing, or `positions` of one: then each line is a hash, and the app's line is hashed to match it.
    """
    got = [line for line in got if line["chords"]]
    if page.get("hidden"):
        drawn_keys, drawn_letters = [line["key"] for line in page["lines"]], [line["at"] for line in page["lines"]]
        got_keys = [hidden(line) for line in got]
    else:
        drawn = expected(page)
        drawn_keys, drawn_letters = [key(line) for line in drawn], [[c[0] for c in line["chords"]] for line in drawn]
        got_keys = [key(line) for line in got]
    matcher = difflib.SequenceMatcher(None, drawn_keys, got_keys, autojunk=False)
    pairs = [(a + k, b + k) for a, b, size in matcher.get_matching_blocks() for k in range(size)]
    return got, drawn_letters, pairs


def compare_page(page, got):
    """Counts of one page: chords on the right letter, on another one, on lines that differ, and so on."""
    counts, wrong = Counter(), []
    got, drawn_letters, pairs = matched_lines(page, got)
    matched = set()
    for drawn_line, app_line_index in pairs:
        matched.add(drawn_line)
        counts["chord lines compared"] += 1
        app_line = got[app_line_index]
        for browser, app in zip(drawn_letters[drawn_line], app_line["chords"]):
            counts["chords compared"] += 1
            if browser != app[0]:
                counts["chords on another letter"] += 1
                wrong.append((app_line["text"], [browser, app[1]], app))
    for k, letters in enumerate(drawn_letters):
        if k not in matched:
            counts["chord lines the app reads as other words or chords"] += 1
            counts["chords on those lines"] += len(letters)
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


def placed(page, got):
    """{(drawing's line, chord): (the drawing's letter, the app's letter, the app's line)} for each chord on a line
    the drawing and the app read alike."""
    got, drawn_letters, pairs = matched_lines(page, got)
    return {(drawn_line, chord): (browser, app[0], got[app_line])
            for drawn_line, app_line in pairs
            for chord, (browser, app) in enumerate(zip(drawn_letters[drawn_line], got[app_line]["chords"]))}


def undrawn(page, got):
    """The app's chord lines the drawing has no line for, by their words and chords, as many times as the app shows each."""
    got, _, pairs = matched_lines(page, got)
    matched = {app_line for _, app_line in pairs}
    return Counter(key(line) for k, line in enumerate(got) if k not in matched), {key(line): line for line in got}


def against(truth, before, after):
    """Chord by chord, two apps against one drawing: each chord is right in both, worse (right before and off
    after), better (off before and right after), or off in both: on the same letter, nearer the drawing's after, or
    farther, which is worse too. A chord on a line before reads as the drawing and after reads as other words is worse
    as well, and one the other way better. So is a chord line after shows that the drawing has none for, more than
    before shows it, and a sheet only before answers. Gives the counts and, for each worse chord, (address, line, chord,
    drawing's letter, before's, after's or None when after reads the line otherwise, the line as the app that placed it
    reads it); for each line after shows that isn't drawn, (address, None, None, None, None, None, the line); and for
    each sheet after doesn't answer, (address, None, None, None, None, None, None)."""
    # The worse counts are shown even at none, as that is what the comparison is for.
    lost, extra, unanswered = ("worse: a line before reads as the drawing, after as other words",
                               "worse: a chord line after shows and the drawing does not", "worse: a sheet only before answers")
    counts = Counter({"worse: right before, off after": 0, "worse: off in both, as far or farther after": 0, lost: 0, extra: 0,
                      unanswered: 0})
    worse = []
    for page in truth:
        a, b = before.get(page["url"]), after.get(page["url"])
        if a is None or b is None:
            if a is not None:
                counts[unanswered] += 1
                worse.append((page["url"], None, None, None, None, None, None))
            elif b is not None:
                counts["better: a sheet only after answers"] += 1
            else:
                counts["sheets neither app answered"] += 1
            continue
        counts["sheets"] += 1
        a_at, b_at = placed(page, a["lines"]), placed(page, b["lines"])
        for place in sorted(a_at.keys() - b_at.keys()):
            counts[lost] += 1
            worse.append((page["url"], *place, a_at[place][0], a_at[place][1], None, a_at[place][2]))
        counts["better: a line after reads as the drawing, before as other words"] += len(b_at.keys() - a_at.keys())
        (a_extra, _), (b_extra, b_lines) = undrawn(page, a["lines"]), undrawn(page, b["lines"])
        for line in sorted(b_extra - a_extra):
            counts[extra] += (b_extra - a_extra)[line]
            worse += [(page["url"], None, None, None, None, None, b_lines[line])] * (b_extra - a_extra)[line]
        counts["better: a chord line before shows and the drawing does not"] += sum((a_extra - b_extra).values())
        for place in sorted(a_at.keys() & b_at.keys()):
            browser, was, now = a_at[place][0], a_at[place][1], b_at[place][1]
            counts["chords compared"] += 1
            if was == browser and now == browser:
                counts["right in both"] += 1
            elif was == browser:
                counts["worse: right before, off after"] += 1
                worse.append((page["url"], *place, browser, was, now, b_at[place][2]))
            elif now == browser:
                counts["better: off before, right after"] += 1
            elif was == now:
                counts["off in both, on the same letter"] += 1
            elif abs(now - browser) < abs(was - browser):
                counts["off in both, nearer after"] += 1
            else:
                counts["worse: off in both, as far or farther after"] += 1
                worse.append((page["url"], *place, browser, was, now, b_at[place][2]))
    return counts, worse


def read_app(path):
    return {row["url"]: row for row in map(json.loads, Path(path).read_text(encoding="utf-8").splitlines())}


# The steps that run on the host.

# The site gets one page at a time, at most one a second: a failure doubles the wait, and this many in a row end the crawl.
PAUSE, LONGEST_PAUSE, FAILURES_IN_A_ROW = 1.1, 60.0, 5


def crawl(directory, urls, limit):
    """Fetches each sheet that isn't in `directory` yet. One the site doesn't send is named and skipped, and the rest
    still come: a page is only kept once it's all here, so the next crawl asks for the missing ones again."""
    (directory / "sheets").mkdir(parents=True, exist_ok=True)
    meta, last, missing, pause, in_a_row = [], None, 0, PAUSE, 0
    for number, url in enumerate(urls[:limit]):
        out = directory / "sheets" / f"{number:04}.html"
        if not out.exists():
            time.sleep(0.0 if last is None else max(0.0, pause - (time.monotonic() - last)))
            last = time.monotonic()
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; research crawler)"})
            try:
                out.write_bytes(urllib.request.urlopen(request, timeout=35).read())
                pause, in_a_row = PAUSE, 0
            except OSError as error:
                said = f"HTTP {error.code}" if isinstance(error, urllib.error.HTTPError) else getattr(error, "reason", error)
                print(f"{url}: {said}", file=sys.stderr)
                missing += 1
                # A guessed address that isn't a sheet is a quick answer. Anything else, such as no answer in time or
                # a refused connection, may be the site under strain: wait longer, and stop if it goes on.
                if not (isinstance(error, urllib.error.HTTPError) and error.code < 500):
                    pause, in_a_row = min(pause * 2, LONGEST_PAUSE), in_a_row + 1
                    if in_a_row == FAILURES_IN_A_ROW:
                        print(f"{in_a_row} failed in a row: the crawl stops here, with what it has", file=sys.stderr)
                        break
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

# The sheet's own address, which its links read against. Loaded from a file, `//x5.shinobi.jp/a.gif` would be
# file://x5.shinobi.jp/a.gif: on Windows the shared folder \\x5.shinobi.jp\a.gif, which the browser waits on, and which
# could hand the machine's sign-in to that host. Against this it's an https address, and the route stops it.
SITE = "https://music.j-total.net/"


def sheet_html(raw, css, faces, size=40):
    """The page as `draw` loads it: J-Total's stylesheet in it, no scripts, links read against `SITE`, and
    Inconsolata from `faces`. At `size` pixels a letter, or at the page's own size (15px) when `size` is None."""
    text = re.sub(r'<link href="/ssi_dat/dat_st.css[^>]*>', lambda _: "<style>" + css + "</style>", raw)
    text = re.sub(r"(?is)<script\b.*?</script[^>]*>", "", text)
    text = re.sub(r"(?i)<head\b[^<>]*+>", lambda found: found[0] + f'<base href="{SITE}">', text, count=1)
    larger = "" if size is None else f" *{{font-size:{size}px !important}}"
    return text.replace("</head>", "<style>" + faces + larger + "</style></head>", 1)


def local(url):
    """Whether the drawing may load `url`: only a file on this machine, which is a file: address with no host."""
    return url.startswith("file:///")


def draw_each(items, draw_one, errors):
    """Each sheet as `draw_one` draws it, with its address. One that fails with one of `errors` is named, on the
    error output and in the list that comes back, and the others are still drawn."""
    pages, failed = [], []
    for item in items:
        try:
            pages.append({**draw_one(item), "url": item["url"]})
        except errors as error:
            failed.append(f"{item['file']} {item['url']}: {str(error).splitlines()[0]}")
            print(f"Not drawn: {failed[-1]}", file=sys.stderr)
    return pages, failed


def draw(directory, fonts=Path("/fonts"), browser_name="chromium", lang=None, out="browser.json", size=40, scale=4.0):
    """Draws every page in `browser_name` on this machine, with its fonts, and writes where the letters are to `out`.
    `size` and `scale` are the letters' size (None for the page's own) and the screen's pixels to a CSS pixel:
    40px at 4 places a letter most exactly, and a reader sees 15px at 1, 1.25 or 1.5."""
    from playwright.sync_api import Error as PlaywrightError, sync_playwright
    from jtotal_widths import FONTS, environment, open_page

    css = (directory / "dat_st.css").read_bytes().decode(CHARSET, "replace")
    faces = "".join(
        f'@font-face{{font-family:Inconsolata;src:url({(fonts / f"inconsolata-{n}.woff2").resolve().as_uri()});unicode-range:{s}}}'
        for n, (_, s) in FONTS.items()
    )
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser, page, _ = open_page(p, browser_name, lang, Path(tmp) / "profile", viewport=(1280, 900), scale=scale)
        page.route("**/*", lambda r: r.continue_() if local(r.request.url) else r.abort())
        html = Path(tmp) / "sheet.html"

        def draw_one(item):
            text = sheet_html((directory / "sheets" / item["file"]).read_bytes().decode(CHARSET, "replace"), css, faces, size)
            # Served as J-Total serves it, in Shift_JIS. A letter the decoder could not read stays the replacement mark.
            html.write_bytes(text.encode(CHARSET, "xmlcharrefreplace"))
            page.goto(html.as_uri())
            page.evaluate("document.fonts.ready")
            return page.evaluate(JS)

        items = [item for item in json.loads((directory / "urls.json").read_text(encoding="utf-8"))
                 if b"<!--HPSTART-->" in (directory / "sheets" / item["file"]).read_bytes()]
        pages, failed = draw_each(items, draw_one, PlaywrightError)
        found = environment(browser, browser_name if "/" not in browser_name else Path(browser_name).stem, "the page's own", fonts)
        browser.close()
    (directory / out).write_text(json.dumps(pages, ensure_ascii=False), encoding="utf-8")
    letters = "the page's own size" if size is None else f"{size}px"
    found += [f"Browser language {lang or 'as Playwright starts it'}", f"Letters at {letters}, {scale} screen pixels to a CSS pixel"]
    found += [f"Not drawn: {line}" for line in failed]
    (directory / out).with_suffix(".environment.txt").write_text("\n".join(found) + "\n", encoding="utf-8")
    print(f"{len(pages)} sheets drawn" + (f", {len(failed)} not" if failed else ""))


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
    parser.add_argument("command", choices=("crawl", "browser", "draw", "app", "compare", "against", "moves", "positions"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("extra", nargs="?")
    parser.add_argument("after", nargs="?", help="against: the tag of the app run to put against DIR/app-EXTRA.jsonl")
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--old", action="store_true")
    parser.add_argument("--show", type=int, default=0)
    parser.add_argument("--fonts", type=Path, default=Path("/fonts"))
    parser.add_argument("--browser", default="chromium")
    parser.add_argument("--lang")
    parser.add_argument("--out", default="browser.json")
    parser.add_argument("--size", default="40", help="draw: the letters' size in pixels, or `page` for the page's own")
    parser.add_argument("--scale", type=float, default=4.0, help="draw: screen pixels to a CSS pixel, as Windows' display scale")
    parser.add_argument("--truth", type=Path, help="the drawing or positions to compare with, if not DIR/browser.json")
    args = parser.parse_args(argv)
    directory = args.directory.resolve()
    if args.command == "crawl":
        crawl(directory, Path(args.extra).read_text(encoding="utf-8").split(), args.limit)
    elif args.command == "browser":
        browser_in_container(directory)
    elif args.command == "draw":
        draw(directory, args.fonts, args.browser, args.lang, args.out, size=None if args.size == "page" else int(args.size), scale=args.scale)
    elif args.command == "app":
        app_run(directory, args.extra, args.old)
    elif args.command == "moves":
        moves_main(directory, Path(args.extra), args.show)
    elif args.command == "against":
        truth = json.loads((args.truth or directory / "browser.json").read_text(encoding="utf-8"))
        counts, worse = against(truth, *(read_app(directory / f"app-{tag}.jsonl") for tag in (args.extra, args.after)))
        for name, value in sorted(counts.items()):
            print(f"{value:7d}  {name}")
        for url, line, chord, browser, was, now, app_line in worse[:args.show]:
            if app_line is None:
                print(f"{url}: {args.after} did not answer")
            elif line is None:
                print(f"{url}: {args.after} shows a chord line the drawing does not: {app_line['text']}")
            else:
                print(f"{url} line {line} chord {chord}: drawn on {browser}, {args.extra} {was}, "
                      f"{args.after} {'reads the line as other words' if now is None else now}: {app_line['text']}")
    elif args.command == "positions":
        Path(args.extra).write_text(json.dumps(positions(json.loads(directory.read_text(encoding="utf-8")))), encoding="utf-8")
    else:
        truth = args.truth or directory / "browser.json"
        total, examples = compare(json.loads(truth.read_text(encoding="utf-8")), read_app(directory / f"app-{args.extra}.jsonl"))
        for name, value in total.items():
            print(f"{value:7d}  {name}")
        for example in examples[:args.show]:
            print(example)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
