#!/usr/bin/env python3
"""How readable the lyrics are over the visuals, from real screenshots on a phone.

  python3 scripts/contrast/contrast.py shots build/contrast/after 0 10 51 98 14 7
  python3 scripts/contrast/contrast.py shots build/contrast/after --shade 0 0 10 98    # the visuals as bright as they're set
  python3 scripts/contrast/contrast.py shots build/contrast/light --theme LIGHT --count 2 10
  python3 scripts/contrast/contrast.py report build/contrast/after
  python3 scripts/contrast/contrast.py report --no-visuals build/contrast/light
  python3 scripts/contrast/contrast.py survey build/contrast/frames    # how bright every preset goes, without a phone

`shots` needs the dev app installed, a music app paused on a song with chords and readings, and the
phone unlocked. For each preset, a number from the sorted list in app/src/main/assets/milkdrop, it
keeps only that one on, plays the song a moment so the dev app follows it, pauses so the lines stay
put, and takes screenshots. Then it hides the visuals for one more, the words alone, which says what's
a word pixel. The dev app's settings are put back after; the song stays a few seconds further on.
Set ANDROID_SERIAL when more than one phone is connected.

`report` gives WCAG contrast of word pixels against the ring right around them, 2 to 8 pixels out,
piece of glyph by piece of glyph, for each kind of text: worst, 5th percentile, median, and how many
pieces are under 4.5 and 3. The lines can sit a few pixels off from the shot without visuals, so the worst
can be a sliver of a glyph measured against itself: look at it before believing it. The last column is
each glyph's stroke alone, without its blended edge.

`survey` needs only what scripts/milkdrop-render/render.py needs, and says how much of the screen would fail
for lines to come at the usual brightness, per preset, brightest first.
"""
import argparse
import html
import importlib.util
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
APP = "com.astrovm.crosstune.dev"
PREFS = "shared_prefs/crosstune_preferences.xml"
AIM = 4.5
# Below the song, above the bar, past where the list fades out at the top: on a 1080 by 2316 screen.
TOP, BOTTOM = 320, 1990
# How much of the screen a tall line takes: shorter rows of text are readings.
WORDS_ROW = 44
# A made-up song, with something in every band for the presets to react to.
SURVEY_BEAT = "0.8*sin(2*PI*55*t)*exp(-8*mod(t,0.5))+0.4*sin(2*PI*330*t)*exp(-25*mod(t+0.25,0.5))+0.25*(random(0)*2-1)*exp(-40*mod(t,0.125))+0.25*sin(2*PI*880*t)*sin(2*PI*0.2*t)"
# A line to come at the usual brightness: its alpha, the visuals' shade with words over them and their ceiling.
WAS = (0.6, 0.725)
NOW = (0.85, 0.725, 56 / 255)


def adb(*command, stdin=None):
    tool = os.environ.get("ADB", str(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"))
    return subprocess.run([tool, *command], input=stdin, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False).stdout


def shell(command):
    return adb("shell", command).decode("utf-8", "replace")


def presets():
    return sorted(p.stem for p in (ROOT / "app/src/main/assets/milkdrop").glob("*.milk"))


def escaped(text):
    return html.escape(text, quote=True).replace("&#x27;", "&apos;")


def read_prefs():
    return adb("exec-out", "run-as", APP, "cat", PREFS)


def write_prefs(xml):
    adb("shell", f"run-as {APP} sh -c {shlex.quote(f'cat > {PREFS}')}", stdin=xml)


def prefs_for(saved, names, preset, shade=None, theme=None, pure_black=False):
    """Only [preset] on, for good. Brightness and theme are the app's own unless asked for, so runs compare."""
    xml = saved.decode()
    for removed in (r'<set name="visuals_off">.*?</set>', r'<set name="visuals_off"\s*/>', r'<long name="visuals_change_ms"[^>]*/>', r'<float name="visuals_shade"[^>]*/>',
                    r'<boolean name="visuals_skip_heavy"[^>]*/>', r'<string name="theme">[^<]*</string>', r'<boolean name="pure_black"[^>]*/>'):
        xml = re.sub(removed, "", xml, flags=re.S)
    off = "".join(f"<string>{escaped(name)}</string>" for name in names if name != preset)
    added = f'<set name="visuals_off">{off}</set><long name="visuals_change_ms" value="{2**63 - 1}" /><boolean name="visuals_skip_heavy" value="false" />'
    if shade is not None:
        added += f'<float name="visuals_shade" value="{shade}" />'
    if theme:
        added += f'<string name="theme">{theme}</string>'
    if pure_black:
        added += '<boolean name="pure_black" value="true" />'
    return xml.replace("</map>", added + "</map>").encode()


TAPS = {}


def tap(description, tries=5):
    """Taps what's described. Reading the screen is slow while it moves, so each place is looked up once."""
    if description not in TAPS:
        for _ in range(tries):
            shell("uiautomator dump /sdcard/contrast.xml >/dev/null 2>&1")
            found = next((html.unescape(node) for node in re.findall(r"<node[^>]*>", adb("exec-out", "cat /sdcard/contrast.xml").decode("utf-8", "replace"))
                          if f'content-desc="{description}"' in html.unescape(node) or f'text="{description}"' in html.unescape(node)), None)
            if found:
                x0, y0, x1, y1 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', found).groups())
                TAPS[description] = ((x0 + x1) // 2, (y0 + y1) // 2)
                break
            time.sleep(0.7)
        else:
            sys.exit(f"Couldn't find {description!r} on the screen: is the phone unlocked?")
    x, y = TAPS[description]
    shell(f"input tap {x} {y}")


def shots(args):
    names = presets()
    saved = read_prefs()
    if not saved:
        sys.exit(f"Couldn't read {APP}'s settings: is the dev app installed?")
    try:
        for number in args.presets:
            shell(f"am force-stop {APP}")
            write_prefs(prefs_for(saved, names, names[number], args.shade, args.theme, args.pure_black))
            print(names[number], flush=True)
            shell("input keyevent KEYCODE_WAKEUP; cmd media_session dispatch play")
            time.sleep(1.5)
            shell(f"monkey -p {APP} -c android.intent.category.LAUNCHER 1")
            time.sleep(2)
            tap("Lyrics of what's playing")
            time.sleep(1.5)
            shell("cmd media_session dispatch pause")
            # Past the first blend, and the lines settled.
            time.sleep(9)
            out = args.out / str(number)
            out.mkdir(parents=True, exist_ok=True)
            for shot in range(args.count):
                shell("input keyevent KEYCODE_WAKEUP")
                (out / f"{shot:02}.png").write_bytes(adb("exec-out", "screencap -p"))
                time.sleep(1.5)
            tap("Visuals options")
            time.sleep(0.8)
            tap("Hide visuals")
            time.sleep(2.5)
            (out / "words.png").write_bytes(adb("exec-out", "screencap -p"))
    finally:
        shell(f"am force-stop {APP}; rm -f /sdcard/contrast.xml")
        write_prefs(saved)


def luminance(rgb):
    import numpy as np
    channel = rgb / 255.0
    channel = np.where(channel <= 0.04045, channel / 12.92, ((channel + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channel[..., 0] + 0.7152 * channel[..., 1] + 0.0722 * channel[..., 2]


def kinds(path):
    """From the shot with no visuals: the word pixels, row by row, and what kind of text each row is."""
    import numpy as np
    from PIL import Image
    from scipy import ndimage
    image = np.array(Image.open(path).convert("RGB")).astype(float)
    gray = image.mean(axis=2)
    ground = np.kron(ndimage.median_filter(gray[::4, ::4], size=25), np.ones((4, 4)))[:gray.shape[0], :gray.shape[1]]
    away = np.abs(gray - ground)
    ink = np.zeros_like(gray, bool)
    ink[TOP:BOTTOM] = away[TOP:BOTTOM] > 38
    ink = ndimage.binary_opening(ink, iterations=1)
    colored = ink & (image.max(axis=2) - image.min(axis=2) > 22)
    per_row, colored_per_row = ink.sum(axis=1), colored.sum(axis=1)
    labels, count = ndimage.label(per_row > 0)
    rows = []
    for label in range(1, count + 1):
        ys = np.where(labels == label)[0]
        top, bottom = int(ys[0]), int(ys[-1]) + 1
        rows.append({"top": top, "bottom": bottom, "colored": colored_per_row[top:bottom].sum() / max(per_row[top:bottom].sum(), 1),
                     "peak": float(np.percentile(away[top:bottom][ink[top:bottom]], 90))})
    words = [row for row in rows if row["colored"] < 0.3 and row["bottom"] - row["top"] >= WORDS_ROW]
    sung = max(words, key=lambda row: row["peak"], default=None)
    for row in rows:
        if row["colored"] >= 0.3:
            under = next((word for word in words if word["top"] >= row["top"]), None)
            row["kind"] = "sung chords" if under is sung else "other chords"
        elif row["bottom"] - row["top"] < WORDS_ROW:
            row["kind"] = "small"
        else:
            row["kind"] = "sung words" if row is sung else "past words" if row["top"] < sung["top"] else "coming words"
    return ink, rows


def lined_up(ink, rows, shots_at):
    """The words moved to where they are in the shots: taking the visuals away can scroll the lines."""
    import numpy as np
    from PIL import Image
    still = np.stack([np.array(Image.open(shot).convert("L")) for shot in shots_at]).min(axis=0).astype(float)
    def at(ys, xs, by):
        return still[np.clip(ys + by, 0, still.shape[0] - 1), xs].mean()
    all_ys, all_xs = np.where(ink)
    overall = max(range(-260, 261, 2), key=lambda by: at(all_ys, all_xs, by))
    moved = {}
    for row in rows:
        part = np.zeros_like(ink)
        part[row["top"]:row["bottom"]] = ink[row["top"]:row["bottom"]]
        ys, xs = np.where(part)
        if len(ys):
            by = max(range(overall - 14, overall + 15), key=lambda by: at(ys, xs, by))
            moved[row["kind"]] = moved.get(row["kind"], np.zeros_like(ink)) | np.roll(part, by, axis=0)
    return moved


def contrasts(kind, light, ring, size=32, step=16):
    """For each piece of glyph: all its pixels against the ring, and its stroke's own color against the ring.

    All of them counts each glyph's blended edge too, which costs dark words on a light ground more than
    light words on a dark one. The stroke is the color WCAG means.
    """
    import numpy as np
    whole, stroke = [], []
    ys = np.where(kind.any(axis=1))[0]
    if not len(ys):
        return whole, stroke
    def ratio(a, b):
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    for y in range(ys[0], ys[-1], step):
        for x in range(0, kind.shape[1] - size, step):
            word, around = kind[y:y + size, x:x + size], ring[y:y + size, x:x + size]
            if word.sum() >= 30 and around.sum() >= 20:
                pixels, behind = light[y:y + size, x:x + size][word], light[y:y + size, x:x + size][around].mean()
                whole.append(ratio(pixels.mean(), behind))
                stroke.append(ratio(np.percentile(pixels, 15 if pixels.mean() < behind else 85), behind))
    return whole, stroke


def report(args):
    import numpy as np
    from PIL import Image
    from scipy import ndimage
    found, strokes = {}, {}
    for preset in sorted(p for p in args.run.iterdir() if (p / "words.png").exists()):
        ink, rows = kinds(preset / "words.png")
        if args.no_visuals:
            at = [preset / "words.png"]
            by_kind = {}
            for row in rows:
                part = np.zeros_like(ink)
                part[row["top"]:row["bottom"]] = ink[row["top"]:row["bottom"]]
                by_kind[row["kind"]] = by_kind.get(row["kind"], np.zeros_like(ink)) | part
        else:
            at = sorted(preset.glob("[0-9]*.png"))
            by_kind = lined_up(ink, rows, at)
        keep = np.zeros_like(ink)
        keep[TOP:BOTTOM] = True
        by_kind = {kind: mask & keep for kind, mask in by_kind.items()}
        every_ink = np.logical_or.reduce(list(by_kind.values()))
        ring = ndimage.binary_dilation(every_ink, iterations=8) & ~ndimage.binary_dilation(every_ink, iterations=2)
        for shot in at:
            light = luminance(np.array(Image.open(shot).convert("RGB")).astype(float))
            for kind, mask in by_kind.items():
                whole, stroke = contrasts(mask, light, ring)
                found.setdefault(kind, []).extend(whole)
                strokes.setdefault(kind, []).extend(stroke)
    print(f'{"":14}{"pieces":>7}{"worst":>7}{"p5":>7}{"median":>8}{"<" + str(AIM):>7}{"<3":>6}{"stroke p5":>11}')
    for kind in ("sung words", "past words", "coming words", "sung chords", "other chords", "small"):
        got = np.array(found.get(kind, []))
        if len(got):
            print(f"{kind:14}{len(got):7}{got.min():7.2f}{np.percentile(got, 5):7.2f}{np.median(got):8.2f}{(got < AIM).mean() * 100:6.1f}%{(got < 3).mean() * 100:5.1f}%{np.percentile(strokes[kind], 5):11.2f}")


def failing(frames, alpha, shade, ceiling=1.0):
    """Per frame, how much of the screen a line [alpha] bright fails 4.5 to 1 on, with the visuals as [shade] and [ceiling] leave them."""
    import numpy as np
    behind = np.minimum(frames.astype(float) * (1 - shade), ceiling * 255)
    words = np.array([0xED, 0xEA, 0xF4], dtype=float)  # OnNight, as the visuals are always under the dark theme's words
    a, b = luminance(words * alpha + behind * (1 - alpha)), luminance(behind)
    return ((np.maximum(a, b) + 0.05) / (np.minimum(a, b) + 0.05) < AIM).mean(axis=(1, 2))


def survey(args):
    import numpy as np
    spec = importlib.util.spec_from_file_location("milkdrop_render", ROOT / "scripts/milkdrop-render/render.py")
    render = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(render)
    width, height, seconds, every = 180, 386, 20, render.FPS * 2
    binary = render.build()
    args.frames.mkdir(parents=True, exist_ok=True)
    audio, cues = args.frames / "beat.f32", args.frames / "cues.txt"
    render.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"aevalsrc='{SURVEY_BEAT}':s=44100:d={seconds + 5}", "-f", "f32le", "-ac", "2", "-ar", "44100", audio])
    rows = []
    for name in presets():
        cues.write_text(f"0 {ROOT / 'app/src/main/assets/milkdrop' / (name + '.milk')}\n")
        try:
            made = subprocess.run([str(binary), audio, cues, str(width), str(height), str(seconds * render.FPS), str(render.PREROLL)],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False, timeout=240).stdout
        except subprocess.TimeoutExpired:
            print(f"{name}: took too long")
            continue
        drawn = np.frombuffer(made, dtype=np.uint8)
        count = drawn.size // (width * height * 4)
        if not count:
            print(f"{name}: drew nothing")
            continue
        frames = drawn[:count * width * height * 4].reshape(count, height, width, 4)[every - 1::every, ..., :3]
        rows.append((failing(frames, *WAS).max(), failing(frames, *NOW).max(), name))
    print("worst frame's share of the screen a line to come fails on: before Issue #688, now")
    for was, now, name in sorted(rows, reverse=True):
        print(f"{was * 100:5.0f}% {now * 100:5.0f}%  {name}")
    for over in (0.05, 0.25, 0.5):
        print(f"over {over * 100:.0f}% of the screen: {sum(row[0] > over for row in rows)} of {len(rows)} before, {sum(row[1] > over for row in rows)} now")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n", maxsplit=1)[0])
    commands = parser.add_subparsers(dest="command", required=True)
    take = commands.add_parser("shots")
    take.add_argument("out", type=Path)
    take.add_argument("presets", type=int, nargs="+")
    take.add_argument("--shade", type=float)
    take.add_argument("--theme", choices=["LIGHT", "DARK"])
    take.add_argument("--pure-black", action="store_true")
    take.add_argument("--count", type=int, default=8)
    measure = commands.add_parser("report")
    measure.add_argument("run", type=Path)
    measure.add_argument("--no-visuals", action="store_true", help="the shot with no visuals, as it is")
    bright = commands.add_parser("survey")
    bright.add_argument("frames", type=Path)
    args = parser.parse_args()
    {"shots": shots, "report": report, "survey": survey}[args.command](args)


if __name__ == "__main__":
    main()
