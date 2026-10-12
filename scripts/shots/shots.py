#!/usr/bin/env python3
"""Draws the store's screenshots and the site's clips in every language, on this computer.

No phone or emulator: the app's real screens are drawn by Robolectric on a clock the test moves
one frame at a time, so every run gives the same pictures. The MilkDrop visuals behind the words
are drawn ahead by scripts/milkdrop-render, from a made-up beat, with the same visual every time.

  python3 scripts/shots/shots.py          # both themes, then copies into docs/, fastlane/ and screenshots/
  python3 scripts/shots/shots.py ja ko    # only these languages
  python3 scripts/shots/shots.py --theme light  # only the light set

Needs ffmpeg, and what scripts/milkdrop-render needs the first time. Run scripts/build-site.py
after, if the site's pages changed too.
"""
import argparse
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
VISUALS = ROOT / "build/shots-visuals"
OUT = ROOT / "app/build/shots"
# The visual the site's English clip showed before these were drawn here.
PRESET = ROOT / "app/src/main/assets/milkdrop/flexi + bdrv - ultramix05 alien complex mix not roaring at all.milk"
# A kick, a snare and a chord, 100 beats a minute: something for the visuals to move to that isn't a song.
BEAT = ("aevalsrc='0.9*sin(2*PI*52*t)*exp(-9*mod(t,0.6))+0.35*(2*random(0)-1)*exp(-18*mod(t+0.3,0.6))"
        "+0.12*sin(2*PI*220*t)+0.08*sin(2*PI*277*t)+0.06*sin(2*PI*330*t)':s=44100:d=20")
# The site's language folders, each with its test class and store listing folder.
LANGS = {
    "en": ("En", "en-US"), "es": ("Es", "es"), "pt-br": ("PtBr", "pt-BR"), "de": ("De", "de-DE"),
    "fr": ("Fr", "fr-FR"), "it": ("It", "it-IT"), "nl": ("Nl", "nl-NL"), "pl": ("Pl", "pl-PL"),
    "ru": ("Ru", "ru-RU"), "tr": ("Tr", "tr-TR"), "id": ("Id", "id"), "hi": ("Hi", "hi-IN"),
    "ja": ("Ja", "ja-JP"), "ko": ("Ko", "ko-KR"), "zh-cn": ("ZhCn", "zh-CN"), "zh-tw": ("ZhTw", "zh-TW"),
}
LOW = ["systemd-run", "--user", "--scope", "--slice=agents.slice", "-q", "nice"] if shutil.which("systemd-run") else ["nice"]


def run(cmd):
    subprocess.run([str(c) for c in cmd], check=True, cwd=ROOT)


def visuals():
    """The clip's visuals at its size, and a short stretch at the store's, drawn once and kept."""
    VISUALS.mkdir(parents=True, exist_ok=True)
    beat = VISUALS / "beat.wav"
    if not beat.exists():
        run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", BEAT, "-ac", "2", beat])
    render = [*LOW, sys.executable, ROOT / "scripts/milkdrop-render/render.py", beat]
    jobs = [(VISUALS / "half.mp4", "540x1200", 7), (VISUALS / "full.mp4", "1080x2400", 3.2)]
    todo = [(out, size, seconds) for out, size, seconds in jobs if not out.exists()]
    with ThreadPoolExecutor(max_workers=1) as pool:
        list(pool.map(lambda job: run([*render, job[0], "--seconds", job[2], "--size", job[1], "--preset", PRESET]), todo))


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("langs", nargs="*", help="language folders, all by default")
    parser.add_argument("--theme", choices=("dark", "light", "both"), default="both")
    args = parser.parse_args(argv)
    args.langs = args.langs or list(LANGS)
    unknown = [lang for lang in args.langs if lang not in LANGS]
    if unknown:
        parser.error(f"No such language: {', '.join(unknown)}. These are: {', '.join(LANGS)}")
    return args


def publish(lang, theme):
    made = OUT / lang / theme
    site = ROOT / "docs/img" / lang
    site.mkdir(parents=True, exist_ok=True)
    for name in ("home", "lyrics"):
        clip = f"{name}-{theme}"
        shutil.copy(made / f"{clip}.mp4", site / f"{clip}.mp4")
        run(["ffmpeg", "-v", "error", "-y", "-i", made / f"{clip}.png", "-q:v", "4", site / f"{clip}.jpg"])
    locale = LANGS[lang][1]
    store = (ROOT / "fastlane/metadata/android" / locale / "images/phoneScreenshots"
             if theme == "dark" else ROOT / "screenshots/light" / locale)
    shots = sorted((made / "store").glob("*.png"))
    if len(shots) != 8:
        raise RuntimeError(f"Expected 8 {theme} screenshots for {lang}, got {len(shots)}")
    store.mkdir(parents=True, exist_ok=True)
    for shot in shots:
        shutil.copy(shot, store / shot.name)
    print(f"{lang} {theme}: 2 clips, {len(shots)} store shots", flush=True)


def main():
    args = arguments()
    themes = ("dark", "light") if args.theme == "both" else (args.theme,)
    visuals()
    tests = [arg for lang in args.langs for theme in themes
             for arg in ("--tests", f"com.astrovm.crosstune.shots.Shots{LANGS[lang][0]}{'Light' if theme == 'light' else ''}")]
    run([*LOW, ROOT / "gradlew", "-q", ":app:shots", f"-PshotsVisuals={VISUALS}", "--max-workers=2", *tests])
    for lang in args.langs:
        for theme in themes:
            publish(lang, theme)


if __name__ == "__main__":
    main()
