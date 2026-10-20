#!/usr/bin/env python3
"""Draws a still of each MilkDrop visual for Settings, into app/src/main/assets/milkdrop-thumbs.

Each preset plays a few seconds to a steady beat, with the same renderer as render.py, and its
last frame is kept as a small square WebP named like the preset. Stills of presets no longer
there are deleted. Run it after adding or removing presets:

  python3 scripts/milkdrop-render/thumbs.py
"""
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("milkdrop_render", Path(__file__).with_name("render.py"))
render = importlib.util.module_from_spec(spec)
spec.loader.exec_module(render)

THUMBS = render.ROOT / "app/src/main/assets/milkdrop-thumbs"
SIZE = 192
SECONDS = 4  # past the first moments, once most presets have filled the screen
# A kick twice a second and a snare between, so presets that follow the beat move as with music.
BEAT = "0.8*sin(2*PI*55*t)*exp(-8*mod(t,0.5))+0.3*sin(2*PI*330*t)*exp(-25*mod(t+0.25,0.5))"


def thumb(binary: Path, audio: Path, cues: Path, out: Path):
    frames = SECONDS * render.FPS
    renderer = subprocess.Popen([str(binary), audio, cues, str(SIZE), str(SIZE), str(frames), str(render.PREROLL)], stdout=subprocess.PIPE)
    # Only the last frame kept, no transparency; OpenGL hands rows bottom first.
    render.encode_frames(renderer, ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{SIZE}x{SIZE}", "-i", "-",
                                    "-vf", f"vflip,select=eq(n\\,{frames - 1}),format=yuv420p", "-frames:v", "1", "-c:v", "libwebp", "-quality", "70", out])


def main():
    presets = sorted(render.PRESETS.glob("*.milk"))
    binary = render.build()
    THUMBS.mkdir(exist_ok=True)
    for old in THUMBS.glob("*.webp"):
        if not (render.PRESETS / f"{old.stem}.milk").exists():
            old.unlink()
    with tempfile.TemporaryDirectory() as tmp:
        audio, cues = Path(tmp) / "beat.f32", Path(tmp) / "cues.txt"
        render.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"aevalsrc='{BEAT}':s=44100:d={SECONDS + 5}",
                    "-f", "f32le", "-ac", "2", "-ar", "44100", audio])
        for i, preset in enumerate(presets, 1):
            cues.write_text(f"0 {preset}\n")
            thumb(binary, audio, cues, THUMBS / f"{preset.stem}.webp")
            print(f"{i}/{len(presets)} {preset.stem}", file=sys.stderr)


if __name__ == "__main__":
    main()
