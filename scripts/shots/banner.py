#!/usr/bin/env python3
"""Redraws the banner from site/banner-dark.html, which shows the English dark stills in docs/img/en.

python3 scripts/shots/banner.py

Writes site/banner-dark.png, the site's link preview (docs/img/og.png) and the store's feature graphic.
Run it after scripts/shots/shots.py, or after changing the banner's page. Needs Chrome or Chromium, and ffmpeg.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGE = ROOT / "site/banner-dark.html"
SIZE = (1024, 500)  # Google Play's feature graphic
COPIES = ["site/banner-dark.png", "docs/img/og.png", "fastlane/metadata/android/en-US/images/featureGraphic.png"]
BROWSERS = ["google-chrome", "chromium", "chromium-browser", "chrome"]


def browser():
    return next((found for found in map(shutil.which, BROWSERS) if found), None)


def main():
    chrome = browser()
    if not chrome or not shutil.which("ffmpeg"):
        sys.exit("banner.py needs Chrome or Chromium, and ffmpeg")
    with tempfile.TemporaryDirectory() as scratch:
        big = Path(scratch) / "big.png"
        # Drawn at twice the size and scaled down, so the small text stays sharp.
        subprocess.run([chrome, "--headless=new", f"--user-data-dir={scratch}/profile", "--hide-scrollbars", "--force-device-scale-factor=2",
                        f"--window-size={SIZE[0]},{SIZE[1]}", f"--screenshot={big}", PAGE.as_uri()], check=True, capture_output=True)
        small = Path(scratch) / "small.png"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", big, "-vf", f"scale={SIZE[0]}:{SIZE[1]}:flags=lanczos", small], check=True)
        for copy in COPIES:
            shutil.copyfile(small, ROOT / copy)
            print(copy)


if __name__ == "__main__":
    main()
