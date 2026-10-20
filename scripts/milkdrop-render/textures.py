#!/usr/bin/env python3
"""Copies the images the app's MilkDrop visuals draw with into app/src/main/assets/milkdrop-textures,
from a checkout of projectM's texture pack, and deletes those none of them use any more. Run it
after adding or removing presets, then thumbs.py:

  git clone https://github.com/projectM-visualizer/presets-milkdrop-texture-pack
  python3 scripts/milkdrop-render/textures.py presets-milkdrop-texture-pack/textures

It names the presets that use an image the pack doesn't have: they draw it blank.
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
PRESETS = ROOT / "app/src/main/assets/milkdrop"
TEXTURES = ROOT / "app/src/main/assets/milkdrop-textures"
# What projectM draws itself: the frame, its blurs and noise.
BUILTIN = re.compile(r"(main|blur[123]|noise_(lq|mq|hq)(_lite)?|noisevol_(lq|hq))")
# fw_, fc_, pw_ and pc_ only say how an image is filtered and wrapped.
MODE = re.compile(r"^[fp][wc]_")
# A random image, of all of them or of those whose name starts so.
RANDOM = re.compile(r"^rand\d\d(?:_(\w+))?$")


def used(preset: str) -> set[str]:
    """The images a preset's shaders sample, by name, lower case and without the mode."""
    names = {MODE.sub("", name.lower()) for name in re.findall(r"\bsampler_(\w+)", preset)}
    return {name for name in names if not BUILTIN.fullmatch(name)}


def main():
    if len(sys.argv) != 2 or not Path(sys.argv[1]).is_dir():
        sys.exit(__doc__)
    pack = {image.stem.lower(): image for image in Path(sys.argv[1]).iterdir() if image.is_file()}
    wanted, missing = set(), {}
    for preset in sorted(PRESETS.glob("*.milk")):
        for name in used(preset.read_text(errors="ignore")):
            random = RANDOM.match(name)
            # Any image does for one picked at random from all of them.
            if random and not random[1]:
                continue
            found = [stem for stem in pack if stem.startswith(random[1])] if random else [name] if name in pack else []
            if found:
                wanted.update(found)
            else:
                missing.setdefault(preset.stem, []).append(name)
    TEXTURES.mkdir(exist_ok=True)
    for old in TEXTURES.iterdir():
        if old.stem.lower() not in wanted:
            old.unlink()
    for name in sorted(wanted):
        shutil.copyfile(pack[name], TEXTURES / pack[name].name)
    print(f"{len(wanted)} images, {sum(f.stat().st_size for f in TEXTURES.iterdir()) // 1024} KB")
    for preset, names in missing.items():
        print(f"missing {', '.join(sorted(names))}: {preset}", file=sys.stderr)


if __name__ == "__main__":
    main()
