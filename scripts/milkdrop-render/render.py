#!/usr/bin/env python3
"""Renders the app's MilkDrop visuals for a song into a video, frame by frame, at a steady 60 fps.

Same projectM, presets and settings as the app, so it looks like the phone, without a screen
recording dropping frames. Builds the renderer from third_party/projectm on first run, into
build/milkdrop-render/. Needs ffmpeg, cmake, ninja, a C++ compiler, and EGL and OpenGL headers.

  python3 scripts/milkdrop-render/render.py song.mp3 out.mp4 --start 121.4 --seconds 7
  python3 scripts/milkdrop-render/render.py song.mp3 out.mp4 --preset 17 --preset 22@4.5 --size 540x1200

--preset takes a number from the list it prints (the presets in app/src/main/assets/milkdrop,
sorted) or a .milk path, optionally @ the second it starts, blending in over 3 s like the app.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
HERE = Path(__file__).resolve().parent
PRESETS = ROOT / "app/src/main/assets/milkdrop"
BUILD = ROOT / "build/milkdrop-render"
FPS = 60
PREROLL = 90  # frames drawn before the start, so the first preset is already moving


def build() -> Path:
    """Copies projectM, makes its clock advance one frame per frame instead of following the
    wall clock, and builds it with the renderer. Skipped once built."""
    binary = BUILD / "render"
    if binary.exists() and binary.stat().st_mtime > (HERE / "render.cpp").stat().st_mtime:
        return binary
    source = ROOT / "third_party/projectm"
    if not (source / "CMakeLists.txt").exists():
        sys.exit("third_party/projectm is missing. Run: git submodule update --init --recursive")
    copy = BUILD / "projectm"
    shutil.rmtree(copy, ignore_errors=True)
    shutil.copytree(source, copy, ignore=shutil.ignore_patterns(".git"))
    clock = copy / "src/libprojectM/TimeKeeper.cpp"
    text = clock.read_text()
    wall = """    auto currentTime = std::chrono::high_resolution_clock::now();

    double currentFrameTime = std::chrono::duration<double>(currentTime - m_startTime).count();"""
    if wall not in text:
        sys.exit(f"projectM's clock changed, update the patch in {Path(__file__).name}")
    clock.write_text(text.replace(wall, f"    double currentFrameTime = m_currentTime + 1.0 / {FPS}.0;"))
    run(["cmake", "-S", copy, "-B", BUILD / "lib", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
         "-DENABLE_SYSTEM_PROJECTM_EVAL=OFF", "-DENABLE_PLAYLIST=OFF", "-DENABLE_INSTALL=OFF"], quiet=True)
    run(["cmake", "--build", BUILD / "lib"], quiet=True)
    lib = BUILD / "lib"
    run(["c++", "-O2", "-std=c++17", f"-DFPS={FPS}", HERE / "render.cpp", "-o", binary,
         "-I", lib / "src/api/include", "-I", copy / "src/api/include",
         lib / "src/libprojectM/libprojectM-4.a", lib / "vendor/projectm-eval/projectm-eval/libprojectM_eval.a", "-lEGL", "-lGL"])
    return binary


def run(command, quiet=False):
    subprocess.run([str(c) for c in command], check=True, stdout=subprocess.DEVNULL if quiet else None)


def preset(arg: str, presets: list[Path]) -> tuple[Path, float]:
    name, _, at = arg.partition("@")
    path = presets[int(name) - 1] if name.isdigit() and 0 < int(name) <= len(presets) else Path(name)
    if not path.is_file():
        sys.exit(f"No preset {name}. Use a number from 1 to {len(presets)} or a .milk file.")
    try:
        return path, float(at or 0)
    except ValueError:
        sys.exit(f"After @ goes the second the preset starts, like 17@4.5, not {arg}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("song", type=Path, help="any audio file ffmpeg reads")
    parser.add_argument("out", type=Path, help="the video to write, H.264")
    parser.add_argument("--start", type=float, default=0, help="where in the song to start, in seconds")
    parser.add_argument("--seconds", type=float, default=10, help="how long, in seconds")
    parser.add_argument("--size", default="540x1200", help="WIDTHxHEIGHT, default the phone's 1080x2400 at half size")
    parser.add_argument("--preset", action="append", default=[], help="number or .milk path, optionally @seconds")
    args = parser.parse_args()

    presets = sorted(PRESETS.glob("*.milk"))
    if not args.preset:
        for i, p in enumerate(presets, 1):
            print(f"{i:3}  {p.stem}")
        sys.exit("Pick at least one --preset.")
    if not args.song.is_file():
        sys.exit(f"No song at {args.song}")
    try:
        width, height = (int(v) for v in args.size.lower().split("x"))
    except ValueError:
        sys.exit(f"--size takes WIDTHxHEIGHT, like 540x1200, not {args.size}")
    cues = [preset(a, presets) for a in args.preset]
    binary = build()

    with tempfile.TemporaryDirectory() as tmp:
        audio, cueFile = Path(tmp) / "audio.f32", Path(tmp) / "cues.txt"
        run(["ffmpeg", "-v", "error", "-y", "-ss", max(0.0, args.start - PREROLL / FPS), "-t", args.seconds + PREROLL / FPS + 1,
             "-i", args.song, "-f", "f32le", "-ac", "2", "-ar", "44100", audio])
        cueFile.write_text("".join(f"{round(at * FPS)} {path}\n" for path, at in sorted(cues, key=lambda c: c[1])))
        frames = round(args.seconds * FPS)
        renderer = subprocess.Popen([str(binary), audio, cueFile, str(width), str(height), str(frames), str(PREROLL)], stdout=subprocess.PIPE)
        # OpenGL hands rows bottom first, so the video is flipped back.
        encode = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{width}x{height}", "-r", FPS, "-i", "-",
                  "-vf", "vflip", "-c:v", "libx264", "-crf", "16", "-pix_fmt", "yuv420p", args.out]
        subprocess.run([str(c) for c in encode], check=True, stdin=renderer.stdout)
        if renderer.wait():
            sys.exit("The renderer failed, see above.")
    print(args.out)


if __name__ == "__main__":
    main()
