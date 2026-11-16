#!/usr/bin/env python3
"""Make fake local songs whose metadata ends with each radio separator.

Run radio_fixtures.py OUT, then open OUT/radio.m3u in VLC at media volume zero.
The artist is always Invented Band. Crosstune must show Radio Fixture for the
first three songs and Radio Fixture - Live for the fourth song.
Needs ffmpeg. No personal media or account is used.
"""
import argparse
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg is required")
    args.out.mkdir(parents=True, exist_ok=True)
    names = []
    for number, ending in enumerate(("-", "–", "—", "- Live"), 1):
        name = f"radio-{number}.mp3"
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=180",
            "-c:a", "libmp3lame", "-b:a", "32k", "-metadata", f"title=Radio Fixture {ending}",
            "-metadata", "artist=Invented Band", "-metadata", f"album=Radio Fixture {number}",
            "-id3v2_version", "3", str(args.out / name),
        ], check=True)
        names.append(name)
    (args.out / "radio.m3u").write_text("#EXTM3U\n" + "\n".join(names) + "\n", encoding="utf-8")
    print(f"Wrote {len(names)} invented songs and {args.out / 'radio.m3u'}")


if __name__ == "__main__":
    main()
