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

TextsTest (in :app:notices) checks the bundled bytes against the SHA-256 and the files in this
checkout, offline. This script checks the web sources: run `check` after changing a line. The
release workflow runs it too, so a text its work changed since is found before a release.
"""

import hashlib
import html.parser
import io
import os
import pathlib
import re
import sys
import tarfile
import urllib.parse
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/test/resources/com/astrovm/crosstune/notice-texts.txt"
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


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "crosstune-notices"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def source_bytes(source: str) -> bytes:
    lines = None
    if re.search(r"#L\d+-L\d+$", source):
        source, span = source.rsplit("#", 1)
        first, last = (int(n) for n in re.findall(r"\d+", span))
        lines = (first, last)
    if source.startswith("html:"):
        raw = download(source[5:])
        charset = re.search(rb'charset="?([\w-]+)', raw)
        data = page_text(raw, charset.group(1).decode() if charset else "utf-8", source[5:])
    elif "!" in source:
        url, inner = source.split("!", 1)
        archive = download(url)
        if url.endswith((".zip", ".jar")):
            data = zipfile.ZipFile(io.BytesIO(archive)).read(inner.lstrip("/"))
        else:
            data = tarfile.open(fileobj=io.BytesIO(archive)).extractfile(inner).read()
    elif source.startswith("https://"):
        data = download(source)
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


def entries():
    for line in MANIFEST.read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            name, sha, source = line.split(maxsplit=2)
            yield name, sha, source


def main(argv):
    command = argv[1] if len(argv) > 1 else "check"
    only = set(argv[2:])
    failed = False
    lines = MANIFEST.read_text().splitlines()
    for name, sha, source in entries():
        if only and name not in only:
            continue
        try:
            data = source_bytes(source)
            bundled_path = TEXTS / name
            bundled_bytes = bundled_path.read_bytes() if command != "fetch" else b""
        except FileNotFoundError as missing:
            # One line, as the other stops do, instead of a traceback.
            sys.exit(f"No {missing.filename}")
        digest = hashlib.sha256(data).hexdigest()
        if command == "fetch":
            (TEXTS / name).write_bytes(data)
            lines = [re.sub(rf"^{re.escape(name)}\s+\S+", f"{name} {digest}", l) for l in lines]
            print(f"wrote {name} {digest[:12]}")
        else:
            bundled = hashlib.sha256(bundled_bytes).hexdigest()
            ok = digest == sha == bundled
            failed |= not ok
            print(f"{'ok  ' if ok else 'DIFF'} {name}  {source}")
    if command == "fetch":
        MANIFEST.write_text("\n".join(lines) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
