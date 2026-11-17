#!/usr/bin/env python3
"""The licence texts in Settings → About → Licenses, copied from where each work publishes it.

Each line of app/src/test/resources/com/astrovm/crosstune/notice-texts.txt names a text in
app/src/main/resources/com/astrovm/crosstune/notices/, its SHA-256 and its source:

  PATH                 a file in this checkout, such as a submodule's LICENSE, at the commit it's on
  ndk:PATH             a file in the NDK that app/build.gradle.kts names, in the Android SDK
  URL                  a file on the web, pinned to a version: a tag or commit in the URL
  ...#L10-L20          only those lines, joined by "\\n" and ended by one
  URL!inner/path       a file inside the zip, jar or .tar.gz at URL
  html:URL             a web page, as text: its blocks apart by blank lines, its links as "text <URL>"

  python3 scripts/notices.py check          fetch every source again and compare it to the bundled bytes
  python3 scripts/notices.py fetch [FILE]   write the bundled texts from their sources, and their SHA-256
  python3 scripts/notices.py fetch-jmdict   refresh both EDRDG pages with the JMdict data
  python3 scripts/notices.py check-release  check the versioned sources and the bundled JMdict snapshot

TextsTest (in :app:notices) checks the bundled bytes against the SHA-256 and the files in this
checkout, offline. This script checks the web sources: run `check` after changing a line. The
release workflow checks the committed JMdict snapshot. Its source pages have no version.
"""

import hashlib
import html.parser
import http.client
import io
import os
import pathlib
import re
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/test/resources/com/astrovm/crosstune/notice-texts.txt"
JMDICT_MANIFEST = MANIFEST.with_name("jmdict-notice-texts.txt")
TEXTS = ROOT / "app/src/main/resources/com/astrovm/crosstune/notices"
BLOCKS = {"p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ol", "ul", "dl", "dt", "dd", "table", "tr", "center", "pre", "blockquote"}


class Text(html.parser.HTMLParser):
    """A page's words, a block per paragraph, with each link's address after its words."""

    def __init__(self, base):
        super().__init__(convert_charrefs=True)
        self.base = base
        self.blocks, self.words, self.links, self.skip = [], [], [], 0

    def flush(self):
        line = re.sub(r"\s+", " ", "".join(self.words)).strip()
        if line:
            self.blocks.append(line)
        self.words = []

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "head", "form"):
            self.skip += 1
        elif tag in BLOCKS:
            self.flush()
        elif tag == "br":
            self.words.append(" ")
        elif tag == "a":
            href = dict(attrs).get("href") or ""
            self.links.append((urllib.parse.urljoin(self.base, href) if href and not href.startswith("#") else "", len(self.words)))

    def handle_endtag(self, tag):
        if tag in ("script", "style", "head", "form"):
            self.skip -= 1
        elif tag in BLOCKS:
            self.flush()
        elif tag == "a" and self.links:
            href, start = self.links.pop()
            label = re.sub(r"\s+", " ", "".join(self.words[start:])).strip()
            if label and href.startswith(("http://", "https://")) and href != label:
                self.words.append(f" <{href}>")

    def handle_data(self, data):
        if not self.skip:
            self.words.append(data)


def page_text(data: bytes, charset: str, base: str) -> bytes:
    parser = Text(base)
    parser.feed(data.decode(charset))
    parser.flush()
    return ("\n\n".join(parser.blocks) + "\n").encode()


class InvalidPage(Exception):
    pass


def download(url: str, validate=None) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "crosstune-notices"})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
                if validate is not None:
                    validate(data)
                return data
        except (TimeoutError, ConnectionError, urllib.error.URLError, http.client.IncompleteRead, InvalidPage) as error:
            permanent = False
            if isinstance(error, urllib.error.HTTPError):
                error.close()
                permanent = error.code not in (408, 429) and error.code < 500
            if permanent or attempt == 3:
                print(f"Could not fetch {url}: {error}", file=sys.stderr)
                if isinstance(error, InvalidPage):
                    sys.exit(str(error))
                raise
            print(f"{url}: attempt {attempt} failed ({error}). Retrying.", file=sys.stderr, flush=True)
            time.sleep(attempt)


def source_bytes(source: str, validate=None) -> bytes:
    lines = None
    if re.search(r"#L\d+-L\d+$", source):
        source, span = source.rsplit("#", 1)
        first, last = (int(n) for n in re.findall(r"\d+", span))
        lines = (first, last)
    if source.startswith("html:"):
        def convert(raw):
            charset = re.search(rb'charset="?([\w-]+)', raw)
            return page_text(raw, charset.group(1).decode() if charset else "utf-8", source[5:])

        raw = download(source[5:], lambda raw: validate(convert(raw))) if validate else download(source[5:])
        data = convert(raw)
    elif "!" in source:
        url, inner = source.split("!", 1)
        archive = download(url)
        if url.endswith((".zip", ".jar")):
            data = zipfile.ZipFile(io.BytesIO(archive)).read(inner.lstrip("/"))
        else:
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                data = tar.extractfile(inner).read()
    elif source.startswith("https://"):
        data = download(source, validate) if validate else download(source)
    elif source.startswith("ndk:"):
        data = (ndk() / source[4:]).read_bytes()
    else:
        data = (ROOT / source).read_bytes()
    if lines:
        first, last = lines
        data = b"\n".join(data.split(b"\n")[first - 1:last]) + b"\n"
    return data


def ndk() -> pathlib.Path:
    version = re.search(r'ndkVersion = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(pathlib.Path.home() / "Android/Sdk")
    folder = pathlib.Path(sdk) / "ndk" / version
    if not folder.is_dir():
        sys.exit(f"No NDK {version} in {sdk}: install it, or set ANDROID_HOME")
    return folder


def entries(manifest):
    for line in manifest.read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            yield line.split(maxsplit=2)


# Check sections through the contact and the final publication. The pages are about 9 and 22 KB.
# Leave room for edits, but reject responses that contain only the headings and a few words.
JMDICT_PAGES = {
    "edrdg-licence.txt": (7000, (
        rb"general dictionary licence statement",
        rb"jmdict - japanese-multilingual dictionary file",
        rb"3\. licence conditions",
        rb"creative commons attribution-sharealike licence",
        rb"acknowledge the usage and source of the files",
        rb"provide copies of the documentation and licence files",
        rb"provide links to either local copies of the documentation and licence files",
        rb"for smartphone and tablet apps, acknowledgement must be made",
        rb"4\. updating dictionary versions",
        rb"procedure for regular updating",
        rb"5\. warranty and liability",
        rb"6\. copyright",
        rb"7\. prior permission",
        rb"8\. special conditions",
        rb"9\. enquiries",
        rb"all enquiries to:",
        rb"\([^\s()@]+@[^\s()@]+\.[^\s()@]+\)",
    )),
    "jmdict-documentation.txt": (16000, (
        rb"====== jmdict-edict dictionary project ======",
        rb"===== current version & download =====",
        rb"===== format =====",
        rb"complete dictionary information in xml format",
        rb"===== lexicographical details =====",
        rb"===== related projects =====",
        rb"===== acknowledgements =====",
        rb"===== publications =====",
        rb"ejdic_report1\.pdf\|\(pdf\)\]\]",
    )),
}


def check_jmdict_page(name, data):
    minimum, sections = JMDICT_PAGES[name]
    if len(data) >= minimum:
        end = 0
        for section in sections:
            found = re.compile(section, re.IGNORECASE).search(data, end)
            if found is None:
                break
            end = found.end()
        else:
            return
    sys.exit(f"Expected EDRDG content in {name}. The snapshot was not changed.")


def fetch_texts(manifest, selected):
    # Download all selected texts first, so a failed download cannot change half the snapshot.
    texts = {}
    for name, _, source in selected:
        if manifest == JMDICT_MANIFEST:
            def validate(data):
                try:
                    check_jmdict_page(name, data)
                    if len(data) < (TEXTS / name).stat().st_size:
                        sys.exit(f"EDRDG content in {name} is shorter than the snapshot. Review it before replacing the snapshot.")
                except SystemExit as error:
                    raise InvalidPage(str(error)) from error

            try:
                data = source_bytes(source, validate=validate)
                validate(data)
            except InvalidPage as error:
                sys.exit(str(error))
        else:
            data = source_bytes(source)
        texts[name] = data
    lines = manifest.read_text().splitlines()
    for name, data in texts.items():
        (TEXTS / name).write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        lines = [re.sub(rf"^{re.escape(name)}\s+\S+", f"{name} {digest}", line) for line in lines]
        print(f"wrote {name} {digest[:12]}")
    manifest.write_text("\n".join(lines) + "\n")


def check_texts(selected, snapshot):
    passed = True
    for name, digest, source in selected:
        bundled = hashlib.sha256((TEXTS / name).read_bytes()).hexdigest()
        expected = digest if snapshot else hashlib.sha256(source_bytes(source)).hexdigest()
        ok = bundled == digest == expected
        passed &= ok
        location = "bundled JMdict snapshot" if snapshot else source
        print(f"{'ok  ' if ok else 'DIFF'} {name}  {location}")
    return passed


def main(argv):
    command = argv[1] if len(argv) > 1 else "check"
    only = set(argv[2:])
    manifests = [JMDICT_MANIFEST] if command == "fetch-jmdict" else [MANIFEST, JMDICT_MANIFEST]
    passed = True
    for manifest in manifests:
        selected = [entry for entry in entries(manifest) if not only or entry[0] in only]
        if not selected:
            continue
        try:
            if command in ("fetch", "fetch-jmdict"):
                fetch_texts(manifest, selected)
            else:
                passed &= check_texts(selected, snapshot=command == "check-release" and manifest == JMDICT_MANIFEST)
        except FileNotFoundError as missing:
            sys.exit(f"No {missing.filename}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
