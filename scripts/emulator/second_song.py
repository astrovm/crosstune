#!/usr/bin/env python3
"""Record two songs per launch route on the shared Samsung, restoring prefs and keeping the phone silent.

Pass the device-bound CLI returned by T3 device_open as --device-cli. Evidence stays
under build/, including screenshots, visible nodes and Android task dumps. No login
or subscription prompts are accepted. Review the evidence before calling a row right.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

APP = 'com.astrovm.crosstune.dev'
SERVICES = {
    'TIDAL': 'TIDAL', 'DEEZER': 'Deezer', 'APPLE_MUSIC': 'Apple Music',
    'AMAZON_MUSIC': 'Amazon Music', 'QOBUZ': 'Qobuz', 'AUDIOMACK': 'Audiomack',
    'SPOTIFY': 'Spotify', 'BANDCAMP': 'Bandcamp', 'SOUNDCLOUD': 'SoundCloud',
    'YOUTUBE': 'YouTube',
    'YOUTUBE_MUSIC@com.google.android.apps.youtube.music': 'YouTube Music',
    'YOUTUBE_MUSIC@app.morphe.android.apps.youtube.music': 'YouTube Music',
}
SEARCHES = [('Crosstune Unreleased First', 'Test Band'), ('Crosstune Unreleased Second', 'Test Band')]
PACKAGES = dict(zip(SERVICES, ['com.aspiro.tidal', 'deezer.android.app', 'com.apple.android.music', 'com.amazon.mp3', 'com.qobuz.music', 'com.audiomack', 'com.spotify.music', 'com.bandcamp.android', 'com.soundcloud.android', 'com.google.android.youtube', 'com.google.android.apps.youtube.music', 'app.morphe.android.apps.youtube.music']))
TEST_APPS = {'com.aspiro.tidal', 'deezer.android.app', 'com.apple.android.music', 'com.amazon.mp3', 'com.qobuz.music', 'com.audiomack', 'com.spotify.music', 'com.bandcamp.android', 'com.soundcloud.android', 'com.google.android.youtube', 'com.google.android.apps.youtube.music', 'app.morphe.android.apps.youtube.music'}
LINKS = ['https://www.deezer.com/track/3135556', 'https://www.deezer.com/track/908604612']
DEEZER_SOURCES = ['https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT', 'https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b']


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, timeout=60, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{command[0]} exited {result.returncode}: {result.stdout}{result.stderr}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    parser.add_argument('--device-cli', required=True)
    parser.add_argument('--service', choices=SERVICES)
    parser.add_argument('--kind', choices=['search', 'link'])
    parser.add_argument('--routes', choices=['all', 'primary'], default='all', help='primary checks card searches and directly shared song links')
    parser.add_argument('--allow-sound', action='store_true', help='Daytime run: preserve the current media volume rather than requiring zero')
    parser.add_argument('--keep-playing', action='store_true', help='Keep apps running between songs to check warm activity reuse')
    parser.add_argument('--output', type=Path, default=Path('build/second-song'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb_cli = str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb')
    devices = run(['timeout', '10', adb_cli, 'devices', '-l'])
    serial = next((row.split()[0] for row in devices.splitlines() if 'SM_S908E' in row and row.split()[1] == 'device'), None)
    if not serial:
        raise SystemExit('Samsung is not attached; refusing to touch another device')

    def adb(*command, **kwargs):
        return run(['timeout', '25', adb_cli, '-s', serial, *command], **kwargs)

    def device(*command):
        return run([args.device_cli, *command])

    def quiet():
        if args.allow_sound:
            return
        volume = adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get')
        if not re.search(r'volume is 0\b', volume):
            raise RuntimeError('Refusing to open an app with nonzero media volume: ' + volume)

    current_name = None

    def capture(name):
        (args.output / (name + '.tasks.txt')).write_text(adb('shell', 'dumpsys', 'activity', 'activities'))
        (args.output / (name + '.tree.txt')).write_text(device('snapshot', '-i'))
        device('screenshot', str((args.output / (name + '.png')).resolve()))

    def stop_playing(cleanup=False):
        if args.keep_playing and not cleanup:
            return
        sessions = adb('shell', 'dumpsys', 'media_session')
        for block in re.split(r'(?=package=)', sessions):
            package = re.match(r'package=([^\s]+)', block)
            if package and re.search(r'state=PlaybackState \{state=PLAYING', block):
                # The untitled YouTube session belongs to the owner's TV.
                description = re.search(r'description=([^\r\n]*)', block)
                title = description.group(1).split(',')[0].strip() if description else ''
                if 'youtube' in package.group(1) and title in ['', 'null', 'None']:
                    continue
                if package.group(1) in TEST_APPS:
                    if current_name:
                        capture(current_name + '.before-stop')
                    adb('shell', 'am', 'force-stop', package.group(1))
                    print('Stopped playback:', package.group(1), flush=True)

    def tap_action(tree, label):
        for attempt in range(2):
            chosen = re.search(r'\[group\] "([^"]+), Choose app"', tree)
            actual_label = chosen.group(1) if chosen else label
            action = next((text for text in [f'Search in {actual_label}', f'Open in {actual_label}'] if '"' + text + '"' in tree), None)
            if action is None:
                return False
            selector = 'label=' + json.dumps(action) + ' enabled=true'
            try:
                device('wait', selector)
                device('press', selector)
                return True
            except RuntimeError as error:
                if attempt or not any(word in str(error).lower() for word in ['selector', 'timeout', 'timed out']):
                    raise
                # An exact-match answer can replace Open with Search while the card is read.
                tree = device('snapshot', '-i')
        return False

    def start(extra):
        quiet()
        # Each launch argument is its own token, including text containing spaces.
        device('open', APP, *[token for item in extra for token in ['--launch-args', item]])

    lock_path = Path('/tmp') / ('claude-' + str(os.getuid())) / 'samsung.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    for target, label in SERVICES.items():
        if args.service and target != args.service:
            continue
        if 'package:' + PACKAGES[target] not in adb('shell', 'pm', 'list', 'packages', PACKAGES[target]).splitlines():
            (args.output / (args.phase + '-' + target + '.not-testable.txt')).write_text('App is not installed on the Samsung.')
            continue
        for kind in ['search', 'link']:
            if args.kind and kind != args.kind:
                continue
            for card in [True, False]:
                if args.routes == 'primary' and card != (kind == 'search'):
                    continue
                prefix = f'{args.phase}-{target}-{kind}-{card}'
                if (args.output / (prefix + '-1.png')).exists():
                    continue
                # Release between pairs, so other agents can use the shared phone.
                with lock_path.open('w') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    saved = adb('shell', 'run-as', APP, 'cat', 'shared_prefs/crosstune_preferences.xml')
                    quiet()
                    try:
                        adb('shell', 'am', 'force-stop', APP)
                        prefs = ET.fromstring(saved)
                        for key, value in [('default_target', target), ('links_in_crosstune', str(card).lower()), ('play_right_away', str(not card).lower())]:
                            entry = prefs.find("*[@name='%s']" % key)
                            if entry is None:
                                entry = ET.SubElement(prefs, 'string' if key == 'default_target' else 'boolean', name=key)
                            if key == 'default_target':
                                entry.text = value
                            else:
                                entry.set('value', value)
                        adb('shell', 'run-as', APP, 'sh', '-c', "'cat > shared_prefs/crosstune_preferences.xml'", input=ET.tostring(prefs, encoding='unicode'))
                        quiet()
                        device('open', APP)
                        for index, (title, artist) in enumerate(SEARCHES):
                            name = prefix + '-' + str(index)
                            current_name = name
                            device('hardware-button', 'wake')
                            adb('shell', 'wm', 'dismiss-keyguard')
                            if kind == 'search':
                                share = f'{title} by {artist}\nhttps://www.google.com/search?q={title.replace(" ", "+")}+by+{artist.replace(" ", "+")}'
                                start(['-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT', share])
                            else:
                                link = (DEEZER_SOURCES if target == 'DEEZER' else LINKS)[index]
                                start(['-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT', link])
                            stop_playing()
                            time.sleep(8)
                            stop_playing()
                            tree = device('snapshot', '-i')
                            if card or kind == 'search':
                                quiet()
                                if tap_action(tree, label):
                                    stop_playing()
                                else:
                                    print('No card action:', name, flush=True)
                            time.sleep(12)
                            stop_playing()
                            capture(name)
                            print('Captured', name, flush=True)
                    finally:
                        try:
                            adb('shell', 'am', 'force-stop', APP)
                            adb('shell', 'run-as', APP, 'sh', '-c', "'cat > shared_prefs/crosstune_preferences.xml'", input=saved)
                            stop_playing(cleanup=True)
                            quiet()
                        finally:
                            device('close')


if __name__ == '__main__':
    main()
