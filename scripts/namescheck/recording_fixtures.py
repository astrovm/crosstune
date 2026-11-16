#!/usr/bin/env python3
"""Make invented local media for RecordingNameCheck. Use VLC at media volume zero."""
import argparse
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    if not shutil.which('ffmpeg'):
        parser.error('ffmpeg is required')
    args.out.mkdir(parents=True, exist_ok=True)
    titles = ['Paperkite (Live)', 'Paperkite Meadow (Live)', 'Paperkite【Live】', 'Paperkite Meadow【Live】', '愛【Cover】', '恋【Cover】', None]
    files = []
    for index, title in enumerate(titles):
        file = '01 Live Forever.mp3' if title is None else f'name-{index}.mp3'
        command = ['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=180', '-c:a', 'libmp3lame', '-b:a', '32k', '-map_metadata', '-1']
        if title is not None:
            command += ['-metadata', f'title={title}', '-metadata', 'artist=Fixture Utaite', '-metadata', f'album=Name Fixture {index}']
        subprocess.run(command + ['-id3v2_version', '3', str(args.out / file)], check=True)
        files.append(file)
    (args.out / 'names.m3u').write_text('#EXTM3U\n' + '\n'.join(files) + '\n', encoding='utf-8')
    print(f'Wrote {len(files)} invented songs')


if __name__ == '__main__':
    main()
