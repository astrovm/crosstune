#!/usr/bin/env python3
"""Record two songs per launch route on the shared Samsung, restoring prefs and keeping the phone silent.

--serial points it at another device instead, such as the crosstune-phone emulator
(emulator-5580). The Samsung stays the default, and is the only one picked by model.
Each pair of songs takes the device through scripts/devicelock.py, so it waits in line
behind other agents and lets go between pairs.

Pass the device-bound CLI returned by T3 device_open as --device-cli. Evidence stays
under build/, including screenshots, visible nodes and Android task dumps. No login
or subscription prompts are accepted. Review the evidence before calling a row right.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import adbtool
import devicelock

APP = 'com.astrovm.crosstune.dev'
SERVICES = {
    'TIDAL': 'TIDAL', 'DEEZER': 'Deezer', 'APPLE_MUSIC': 'Apple Music',
    'AMAZON_MUSIC': 'Amazon Music', 'QOBUZ': 'Qobuz', 'PRESTO_MUSIC': 'Presto Music', 'AUDIOMACK': 'Audiomack',
    'SPOTIFY': 'Spotify', 'BANDCAMP': 'Bandcamp', 'SOUNDCLOUD': 'SoundCloud',
    'YOUTUBE': 'YouTube',
    'YOUTUBE_MUSIC@com.google.android.apps.youtube.music': 'YouTube Music',
    'YOUTUBE_MUSIC@app.morphe.android.apps.youtube.music': 'YouTube Music',
}
SEARCHES = [('Crosstune Unreleased First', 'Test Band'), ('Crosstune Unreleased Second', 'Test Band')]
PACKAGES = dict(zip(SERVICES, ['com.aspiro.tidal', 'deezer.android.app', 'com.apple.android.music', 'com.amazon.mp3', 'com.qobuz.music', 'com.prestomusic.app', 'com.audiomack', 'com.spotify.music', 'com.bandcamp.android', 'com.soundcloud.android', 'com.google.android.youtube', 'com.google.android.apps.youtube.music', 'app.morphe.android.apps.youtube.music']))
TEST_APPS = {'com.aspiro.tidal', 'deezer.android.app', 'com.apple.android.music', 'com.amazon.mp3', 'com.qobuz.music', 'com.prestomusic.app', 'com.audiomack', 'com.spotify.music', 'com.bandcamp.android', 'com.soundcloud.android', 'com.google.android.youtube', 'com.google.android.apps.youtube.music', 'app.morphe.android.apps.youtube.music'}
LINKS = ['https://www.deezer.com/track/3135556', 'https://www.deezer.com/track/908604612']
# The test emulators, by name, as emulator.py makes them and devicelock.py shares them.
EMULATORS = json.loads((Path(__file__).resolve().parent / 'emulators.json').read_text())
DEVICELOCK = Path(__file__).resolve().parents[1] / 'devicelock.py'
# How long one pair of songs holds the device: about a minute, with room for a slow app and for putting the app back.
PAIR_MINUTES = 10
# The longest one command runs, and the time kept at the end of a turn to put the app back as it was (force-stop, the
# settings, stopping the music, the volume, closing the session): five of the longest commands, though each takes seconds.
COMMAND_SECONDS = 60
RESTORE_SECONDS = 5 * COMMAND_SECONDS
# When, by time.monotonic(), the device's turn ends, less the time kept for what's left; None out of a turn. No command
# starts that could still be running then, so a stuck pair stops before the lock lets go of the device under it.
deadline = None
DEEZER_SOURCES = ['https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT', 'https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b']


def run(command, **kwargs):
    if deadline is not None and time.monotonic() + COMMAND_SECONDS > deadline:
        raise TimeoutError(f'{command[0]} could run past the turn on the device, which another agent may take then')
    result = subprocess.run(command, capture_output=True, text=True, timeout=COMMAND_SECONDS, check=False, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{command[0]} exited {result.returncode}: {result.stdout}{result.stderr}')
    return result.stdout


def shared_device(serial, devices):
    """The name devicelock.py shares serial's device by: the Samsung when its row in devices, what `adb devices -l`
    prints, has the Samsung's model, even passed with --serial and next to another; a test emulator by its port. None
    for any other device, which no one else takes turns on."""
    row = next((line.split() for line in devices.splitlines() if line.split()[:1] == [serial]), [])
    if f'model:{adbtool.SAMSUNG_MODEL}' in row:
        return 'samsung'
    return next((name for name, spec in EMULATORS.items() if serial == f"emulator-{spec['port']}"), None)


@contextmanager
def turn(device, who):
    """Holds device through devicelock.py while the block runs, waiting in line behind other agents, and lets it go
    after; it holds nothing for None. The holder lets go by itself after PAIR_MINUTES, should this stop on the way, so
    commands stop RESTORE_SECONDS before that. The block gets a function to call when it starts putting the app back,
    which lets commands run until the turn's end."""
    global deadline
    if device is None:
        yield lambda: None
        return
    holder = subprocess.run([sys.executable, str(DEVICELOCK), 'take', device, '--who', who, '--minutes', str(PAIR_MINUTES)], check=True, capture_output=True, text=True).stdout.split()[-1]
    end = None

    def restoring():
        global deadline
        deadline = end

    try:
        end = lease_end(holder)
        deadline = end - RESTORE_SECONDS
        yield restoring
    finally:
        deadline = None
        # This holder only: once its turn is over, the device may be another agent's turn, even from this session.
        subprocess.run([sys.executable, str(DEVICELOCK), 'release', device, '--holder', holder], check=True, stdout=subprocess.DEVNULL)


def lease_end(holder):
    """When, by time.monotonic(), the turn devicelock.py's holder (the pid take printed) keeps ends: the "ends" it
    writes in its note, by the same clock it lets go by. take can return a while after the holder's turn started."""
    note = devicelock.read_note(devicelock.holder_path(int(holder)))
    if not note or not isinstance(note.get('ends'), (int, float)):
        raise RuntimeError(f'devicelock.py took the device but left no end for holder {holder}')
    return note['ends']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    parser.add_argument('--device-cli', required=True)
    parser.add_argument('--serial', help='adb serial to use instead of the Samsung, e.g. emulator-5580')
    parser.add_argument('--service', choices=SERVICES)
    parser.add_argument('--kind', choices=['search', 'link'])
    parser.add_argument('--routes', choices=['all', 'primary'], default='all', help='primary checks card searches and directly shared song links')
    parser.add_argument('--allow-sound', action='store_true', help='Daytime run: preserve the current media volume rather than requiring zero')
    parser.add_argument('--keep-playing', action='store_true', help='Keep apps running between songs to check warm activity reuse')
    parser.add_argument('--output', type=Path, default=Path('build/second-song'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb_cli = adbtool.path()
    devices = run(['timeout', '10', adb_cli, 'devices', '-l'])
    try:
        samsung = adbtool.samsung_serial(devices)
    except ValueError:
        samsung = None
    wanted = args.serial or samsung
    serial = next((row.split()[0] for row in devices.splitlines() if row.split()[0] == wanted and row.split()[1] == 'device'), None)
    if not serial:
        raise SystemExit(f'{args.serial or "Samsung"} is not attached; refusing to touch another device')
    where = 'the emulator' if args.serial else 'the Samsung'

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

    shared = shared_device(serial, devices)
    who = f'second_song.py {args.phase} {os.getpid()}'
    for target, label in SERVICES.items():
        if args.service and target != args.service:
            continue
        if 'package:' + PACKAGES[target] not in adb('shell', 'pm', 'list', 'packages', PACKAGES[target]).splitlines():
            (args.output / (args.phase + '-' + target + '.not-testable.txt')).write_text(f'App is not installed on {where}.')
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
                with turn(shared, who) as restoring:
                    saved = adb('shell', 'run-as', APP, 'cat', 'shared_prefs/crosstune_preferences.xml')
                    quiet()
                    try:
                        adb('shell', 'am', 'force-stop', APP)
                        prefs = ET.fromstring(saved)
                        for key, value in [('default_target', target), ('links_in_crosstune', str(card).lower()), ('play_right_away', str(not card).lower())]:
                            entry = prefs.find(f"*[@name='{key}']")
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
                        restoring()
                        try:
                            adb('shell', 'am', 'force-stop', APP)
                            adb('shell', 'run-as', APP, 'sh', '-c', "'cat > shared_prefs/crosstune_preferences.xml'", input=saved)
                            stop_playing(cleanup=True)
                            quiet()
                        finally:
                            device('close')


if __name__ == '__main__':
    main()
