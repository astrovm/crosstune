#!/usr/bin/env python3
"""Measure daily practice reminder delivery on a test phone."""

from contextlib import contextmanager
import datetime
import json
import math
import os
import re
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo
import statistics


def summarize(lateness):
    """Return the median and worst lateness in seconds for complete runs."""
    if not lateness or any(not math.isfinite(value) for value in lateness):
        raise ValueError('Lateness must contain finite measurements')
    return statistics.median(lateness), max(lateness)


def prepare_preferences(data, minute):
    """Set one fake due card and the reminder in a copy of Android preferences."""
    if not 0 <= minute < 1440:
        raise ValueError('Minute must be within a day')
    root = ET.fromstring(data)
    if root.tag != 'map':
        raise ValueError('Expected Android preferences')
    values = {
        'practice_reminder_on': ('boolean', 'true'),
        'practice_reminder_turned_off': ('boolean', 'false'),
        'practice_reminder_minute': ('int', str(minute)),
        'practice_reminder_ignored': ('int', '0'),
        'practice_reminder_done_at': ('long', '0'),
        'practice_reminder_posted_for': ('long', '0'),
    }
    for child in list(root):
        if child.get('name') in values or child.get('name') in {'saved_lines', 'card_types'}:
            root.remove(child)
    for name, (kind, value) in values.items():
        ET.SubElement(root, kind, name=name, value=value)
    ET.SubElement(root, 'string', name='card_types').text = '[]'
    card = {'text': 'Stars', 'translation': 'Meaning', 'title': 'Demo', 'artist': 'Band', 'due': 0}
    ET.SubElement(root, 'string', name='saved_lines').text = json.dumps([card])
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def target_time(now_ms, lead_minutes, zone):
    """Pick a wall-clock minute at least the requested number of minutes away."""
    if lead_minutes < 1:
        raise ValueError('Lead must be positive')
    target = ((now_ms + lead_minutes * 60_000 + 59_999) // 60_000) * 60_000
    local = datetime.datetime.fromtimestamp(target / 1000, ZoneInfo(zone))
    return target, local.hour * 60 + local.minute


def samsung_serial(listing):
    """Pick the one online SM-S908E, without connecting to other devices."""
    found = []
    for line in listing.splitlines():
        match = re.fullmatch(r'(.+?)\s+device\s+(.+)', line.strip())
        if match and 'model:SM_S908E' in match.group(2).split():
            found.append(match.group(1))
    if len(found) != 1:
        raise ValueError('Expected exactly one online Samsung SM-S908E')
    return found[0]


def notification_posted_at(dump):
    """Read the reminder's system creation time in milliseconds, or None if absent."""
    records = re.split(r'(?=NotificationRecord\()', dump)
    matches = []
    found = False
    excerpts = []
    for record in records[1:]:
        header = record.splitlines()[0]
        if not re.search(r'\bpkg=com\.astrovm\.crosstune\.dev(?:\s|\))', header):
            continue
        if not re.search(r'\bid=5(?:\s|\))', header):
            continue
        found = True
        excerpts.append('\n'.join(record.splitlines()[:60]))
        creation = re.search(r'^\s*mCreationTimeMs=(\d+)\s*$', record, re.MULTILINE)
        if creation:
            matches.append(int(creation.group(1)))
    if found and not matches:
        raise ValueError('Reminder record has no creation time:\n' + '\n'.join(excerpts[:3]))
    if len(matches) > 1:
        raise ValueError('More than one reminder record')
    return matches[0] if matches else None


def notification_permission(dump):
    """Read the runtime notification permission and its flags from the package dump."""
    pattern = r'^\s*android\.permission\.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]\s*$'
    rows = re.findall(pattern, dump, re.MULTILINE)
    if len(rows) != 1:
        raise ValueError('Expected one notification permission state')
    granted, flags = rows[0]
    return granted == 'true', {flag.strip() for flag in flags.split('|') if flag.strip()}


@contextmanager
def temporary_preferences(phone, minute, backup):
    """Restore the original app preferences and re-arm them even if a run fails."""
    phone.stop()
    try:
        original = phone.read_preferences()
        fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, 'wb') as saved:
                saved.write(original)
                saved.flush()
                os.fsync(saved.fileno())
        except BaseException:
            backup.unlink(missing_ok=True)
            raise
    except BaseException:
        phone.launch()
        raise
    try:
        phone.write_preferences(prepare_preferences(original, minute))
        phone.launch()
        yield
    finally:
        phone.stop()
        phone.write_preferences(original)
        phone.launch()
        backup.unlink()


class Phone:
    """Commands sent only to the Samsung selected from the SDK ADB listing."""

    package = 'com.astrovm.crosstune.dev'
    preferences = 'shared_prefs/crosstune_preferences.xml'

    def __init__(self, adb, serial):
        self.adb = str(adb)
        self.serial = serial

    def command(self, *args, data=None):
        import subprocess

        try:
            result = subprocess.run(
                [self.adb, '-s', self.serial, *args], input=data, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, timeout=30, check=False,
            )
        except subprocess.TimeoutExpired:
            raise RuntimeError('ADB command timed out') from None
        if result.returncode:
            message = result.stderr.decode('utf-8', errors='replace').replace(self.serial, '[device]')
            raise RuntimeError(f'ADB command failed: {message.strip()}')
        return result.stdout

    def shell(self, command, data=None):
        return self.command('shell', command, data=data)

    def stop(self):
        self.shell(f'am force-stop {self.package}')

    def launch(self):
        result = self.shell(f'am start -n {self.package}/com.astrovm.crosstune.MainActivity').decode(errors='replace')
        if re.search(r'^(?:Error|Exception)', result, re.MULTILINE):
            raise RuntimeError(f'Could not launch the dev app: {result.strip()}')

    def read_preferences(self):
        return self.shell(f'run-as {self.package} cat {self.preferences}')

    def write_preferences(self, data):
        self.shell(f"run-as {self.package} sh -c 'cat > {self.preferences}'", data=data)


    def exact_mode(self):
        dump = self.shell(f'appops get --user 0 {self.package} SCHEDULE_EXACT_ALARM').decode()
        return exact_alarm_appop(dump)

    def set_exact_mode(self, mode):
        if mode not in {'allow', 'ignore', 'deny', 'default'}:
            raise ValueError('Unknown app-op mode')
        self.shell(f'appops set --user 0 {self.package} SCHEDULE_EXACT_ALARM {mode}')

    def notification_state(self):
        dump = self.shell(f'dumpsys package {self.package}').decode()
        granted, flags = notification_permission(dump)
        return granted, frozenset(flags)

    def set_notification_state(self, state):
        granted, flags = state
        permission = 'android.permission.POST_NOTIFICATIONS'
        current, current_flags = self.notification_state()
        if granted != current:
            action = 'grant' if granted else 'revoke'
            self.shell(f'pm {action} --user 0 {self.package} {permission}')
            _, current_flags = self.notification_state()
        for flag in ('USER_SET', 'USER_FIXED', 'REVOKED_COMPAT', 'REVIEW_REQUIRED', 'REVOKE_WHEN_REQUESTED'):
            if (flag in flags) != (flag in current_flags):
                action = 'set' if flag in flags else 'clear'
                name = flag.lower().replace('_', '-')
                self.shell(f'pm {action}-permission-flags --user 0 {self.package} {permission} {name}')

    def notification_appop_state(self):
        dump = self.shell(f'appops get --user 0 {self.package} POST_NOTIFICATION').decode()
        return notification_appop(dump)

    def restore_notification_appop(self, original):
        if self.notification_appop_state() == original:
            return
        mode = original[0] or original[1]
        self.shell(f'appops set --user 0 --uid {self.package} POST_NOTIFICATION {mode}')
        if self.notification_appop_state() != original:
            raise RuntimeError('Could not restore notification app-op')

    def battery_override(self):
        dump = self.shell('dumpsys battery').decode()
        if 'Current Battery Service state:' not in dump:
            raise ValueError('Battery state is missing')
        return 'UPDATES STOPPED' in dump

    def set_battery_override(self, enabled):
        action = 'unplug' if enabled else 'reset'
        self.shell(f'dumpsys battery {action}')

    def forced_idle(self):
        dump = self.shell('dumpsys deviceidle').decode()
        result = re.search(r'^\s*mForceIdle=(true|false)\s*$', dump, re.MULTILINE)
        if not result:
            raise ValueError('Device idle state is missing')
        return result.group(1) == 'true'

    def set_forced_idle(self, enabled):
        action = 'force-idle' if enabled else 'unforce'
        self.shell(f'cmd deviceidle {action}')


def exact_alarm_appop(dump):
    """Read the app-op mode, including the default reported for no explicit operation."""
    modes = re.findall(r'^\s*SCHEDULE_EXACT_ALARM:\s*([a-z]+)(?:[;\s]|$)', dump, re.MULTILINE)
    if not modes and re.search(r'^No operations\.\s*$', dump, re.MULTILINE):
        modes = re.findall(r'^Default mode:\s*([a-z]+)\s*$', dump, re.MULTILINE)
    if len(modes) != 1 or modes[0] not in {'allow', 'ignore', 'deny', 'default'}:
        raise ValueError('Expected one known exact-alarm app-op mode')
    return modes[0]


@contextmanager
def temporary_setting(read, write, value):
    """Put a device setting back, including after a partially failed change."""
    original = read()
    if original == value:
        yield
        return
    try:
        write(value)
        if read() != value:
            raise RuntimeError('Device setting did not change')
        yield
    finally:
        write(original)
        if read() != original:
            raise RuntimeError('Could not restore device setting')


def screen_on(dump):
    """Read whether the screen is on from the device-idle service."""
    result = re.search(r'^\s*mScreenOn=(true|false)\s*$', dump, re.MULTILINE)
    if not result:
        raise ValueError('Screen state is missing')
    return result.group(1) == 'true'


def last_wake(dump):
    """Read the power service's most recent wake time, in its own clock."""
    result = re.search(r'\bmLastWakeTime=(\d+)', dump)
    if not result:
        raise ValueError('Last wake time is missing')
    return int(result.group(1))



def reminder_alarm(dump, target, exact):
    """Find this reminder's scheduled wakeup and check its target and window."""
    blocks = re.split(r'(?=^[ \t]*(?:RTC|ELAPSED)(?:_WAKEUP)? #\d+: Alarm\{)', dump, flags=re.MULTILINE)
    matches = []
    for block in blocks:
        header = block.splitlines()[0] if block.splitlines() else ''
        if not re.search(rf'\borigWhen {target}\b', header):
            continue
        if not re.search(r'\bcom\.astrovm\.crosstune\.dev\}', header):
            continue
        if not re.search(r'com\.astrovm\.crosstune\.action\.REMIND_PRACTICE(?![A-Za-z0-9_])', block):
            continue
        window = re.search(r'\bwindow=(\S+)', block)
        if not window or (window.group(1) == '0') != exact:
            raise ValueError('The reminder alarm has an unexpected window')
        matches.append(block)
    if len(matches) != 1:
        raise ValueError('Expected one reminder alarm at the target')
    return matches[0]

def run_measurement(phone, minutes, wait_minutes, force_idle, exact, backup):
    """Run one bounded measurement; restore every setting before returning."""
    import time
    from contextlib import ExitStack

    if minutes < 1 or wait_minutes < 1 or minutes + wait_minutes > 38:
        raise ValueError('Lead and wait must total at most 38 minutes')
    if phone.battery_override() or phone.forced_idle():
        raise RuntimeError('Battery or Doze is already overridden; refusing to change it')
    clock = int(phone.shell('date +%s').decode().strip()) * 1000
    clock_offset = clock / 1000 - time.time()
    zone = phone.shell('getprop persist.sys.timezone').decode().strip()
    target, minute = target_time(clock, minutes, zone)
    permission = phone.notification_state()
    requested_permission = (True, permission[1])
    mode = 'allow' if exact else 'ignore'
    with ExitStack() as stack:
        stack.enter_context(temporary_setting(phone.exact_mode, phone.set_exact_mode, mode))
        original_appop = phone.notification_appop_state()
        stack.callback(phone.restore_notification_appop, original_appop)
        stack.enter_context(temporary_setting(phone.notification_state, phone.set_notification_state, requested_permission))
        active_appop = phone.notification_appop_state()
        if (active_appop[0] or active_appop[1]) != 'allow':
            raise RuntimeError('Notification app-op is not allowed; the run did not start')
        stack.enter_context(temporary_setting(phone.battery_override, phone.set_battery_override, True))
        stack.enter_context(temporary_preferences(phone, minute, backup))
        for attempt in range(20):
            alarm = phone.shell('dumpsys alarm').decode(errors='replace')
            try:
                scheduled = reminder_alarm(alarm, target, exact)
                break
            except ValueError:
                if attempt == 19:
                    lines = [line for line in alarm.splitlines() if 'crosstune.dev' in line or 'REMIND_PRACTICE' in line]
                    detail = '\n'.join(lines[:30]) or '(no dev-app alarm in the dump)'
                    logs = phone.command('logcat', '-d', '-v', 'epoch', '-s', 'AndroidRuntime', 'ActivityManager', 'ActivityTaskManager').decode(errors='replace')
                    app_lines = [line for line in logs.splitlines() if 'com.astrovm.crosstune.dev' in line]
                    recent = '\n'.join(app_lines[-10:]) or '(no dev-app startup log)'
                    raise RuntimeError(f'The app did not arm the expected alarm for {target}:\n{detail}\n{recent}') from None
                time.sleep(1)
        print('Scheduled alarm:\n' + scheduled.strip(), flush=True)
        phone.shell('input keyevent 223')
        for _ in range(10):
            if not screen_on(phone.shell('dumpsys deviceidle').decode()):
                break
            time.sleep(1)
        else:
            raise RuntimeError('The screen did not turn off')
        if int(phone.shell('date +%s').decode().strip()) * 1000 >= target - 10_000:
            raise RuntimeError('Setup finished too close to the target; discard the run')
        wake_before = last_wake(phone.shell('dumpsys power').decode())
        if force_idle:
            stack.enter_context(temporary_setting(phone.forced_idle, phone.set_forced_idle, True))
        print(f'Target epoch ms: {target}. Waiting for the run to end.', flush=True)
        deadline = target / 1000 + wait_minutes * 60 - clock_offset
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            time.sleep(min(30, remaining))
        wake_after = last_wake(phone.shell('dumpsys power').decode())
        notification = phone.shell('dumpsys notification').decode(errors='replace')
        log = phone.command('logcat', '-d', '-v', 'epoch', '-s', 'AlarmManager', 'NotificationService', 'FreecessController', 'MARsPolicyManager').decode(errors='replace')
        relevant = []
        for line in log.splitlines():
            match = re.match(r'^\s*(\d+(?:\.\d+)?)\s+', line)
            if match and float(match.group(1)) >= clock / 1000 and (
                'crosstune.dev' in line or 'freecess' in line.lower()
            ):
                relevant.append(line)
        try:
            posted = notification_posted_at(notification)
        except ValueError:
            for line in relevant:
                if 'com.astrovm.crosstune.dev' in line:
                    print(line.replace(phone.serial, '[device]'), flush=True)
            raise
        if wake_before != wake_after or screen_on(phone.shell('dumpsys deviceidle').decode()):
            raise RuntimeError('The phone woke during the run; discard this measurement')
        if posted is not None and posted < target:
            raise RuntimeError('The notification predates the target; discard this measurement')
        return target, posted, relevant


@contextmanager
def restore_screen(phone):
    """Restore the screen state after all app and system cleanup."""
    before = screen_on(phone.shell('dumpsys deviceidle').decode())
    try:
        yield
    finally:
        after = screen_on(phone.shell('dumpsys deviceidle').decode())
        if after != before:
            import time

            phone.shell(f'input keyevent {224 if before else 223}')
            for _ in range(10):
                if screen_on(phone.shell('dumpsys deviceidle').decode()) == before:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Could not restore screen state')


def lock_seconds_left(output, who, now):
    """Read the remaining time on this run's Samsung lock."""
    pattern = rf'^samsung: held by {re.escape(who)} pid \d+ .*? until (\S+)'
    match = re.search(pattern, output, re.MULTILINE)
    if not match:
        raise ValueError('This run does not hold the Samsung lock')
    expires = datetime.datetime.fromisoformat(match.group(1).rstrip(';'))
    return (expires - now).total_seconds()


def main():
    import argparse
    import signal
    import subprocess
    import sys
    from pathlib import Path

    parser = argparse.ArgumentParser(description='Measure one practice reminder on the locked Samsung.')
    parser.add_argument('--minutes', type=int, default=20, help='minimum minutes until the reminder')
    parser.add_argument('--wait-minutes', type=int, default=15, help='minutes to wait after the target')
    parser.add_argument('--force-idle', action='store_true', help='force Doze for this run')
    parser.add_argument('--exact', action='store_true', help='allow exact alarms for this run')
    parser.add_argument('--recover', action='store_true', help='restore a prior interrupted run')
    parser.add_argument('--who', default='issue 773', help='owner name used to take the Samsung lock')
    args = parser.parse_args()
    if args.minutes < 1 or args.wait_minutes < 1 or args.minutes + args.wait_minutes > 38:
        parser.error('lead and wait must total at most 38 minutes')

    lock_script = Path(__file__).resolve().parents[1] / 'devicelock.py'
    lock = subprocess.run([sys.executable, str(lock_script), 'who', 'samsung'], capture_output=True, text=True, check=True)
    remaining = lock_seconds_left(lock.stdout, args.who, datetime.datetime.now().astimezone())
    required = 180 if args.recover else (args.minutes + args.wait_minutes) * 60 + 180
    if remaining < required:
        subprocess.run([sys.executable, str(lock_script), 'release', 'samsung', '--who', args.who], check=True)
        raise RuntimeError('The Samsung lock does not have enough time left for this run')

    def interrupted(signum, _frame):
        raise InterruptedError(f'Interrupted by signal {signum}')

    signal.signal(signal.SIGTERM, interrupted)
    try:
        adb = Path.home() / 'Android/Sdk/platform-tools/adb'
        listing = subprocess.run([str(adb), 'devices', '-l'], capture_output=True, text=True, check=True).stdout
        phone = Phone(adb, samsung_serial(listing))
        if media_volume(phone.shell('cmd media_session volume --stream 3 --get').decode()) != 0:
            raise RuntimeError('Media volume is not zero; refusing to start')
        cache = Path('/mnt/data/agent-cache/crosstune-773')
        backup = cache / 'preferences-backup.xml'
        journal = cache / 'device-state.json'
        if args.recover:
            original = json.loads(journal.read_text(encoding='utf-8'))
            recover_state(phone, original, backup)
            journal.unlink()
            print('Original device state restored.')
            return
        if backup.exists():
            raise RuntimeError('A preference backup exists; recover it before starting a new run')
        original = capture_state(phone)
        write_journal(journal, original)
        try:
            with restore_screen(phone):
                target, posted, log = run_measurement(phone, args.minutes, args.wait_minutes, args.force_idle, args.exact, backup)
            if capture_state(phone) != original:
                raise RuntimeError('Device state did not match the original after the run')
        except BaseException as error:
            try:
                recover_state(phone, original, backup)
                journal.unlink()
            except Exception as recovery_error:
                raise RuntimeError(f'Recovery is needed: {recovery_error}') from error
            raise
        journal.unlink()
        if posted is None:
            print(f'No notification observed by {args.wait_minutes * 60} s after the target.')
        else:
            print(f'Notification lateness: {(posted - target) / 1000:.3f} s')
        for line in log:
            if 'com.astrovm.crosstune.dev' in line:
                print(line.replace(phone.serial, '[device]'))
    finally:
        subprocess.run([sys.executable, str(lock_script), 'release', 'samsung', '--who', args.who], check=True)


def notification_appop(dump):
    """Read a UID notification mode or its untouched default; reject package overrides."""
    package_modes = re.findall(r'^\s*POST_NOTIFICATION:\s*([a-z]+)(?:[;\s]|$)', dump, re.MULTILINE)
    if len(package_modes) > 1 or any(mode != 'allow' for mode in package_modes):
        raise ValueError('A non-default package notification app-op is present')
    modes = re.findall(r'^\s*Uid mode:\s*POST_NOTIFICATION:\s*([a-z]+)(?:[;\s]|$)', dump, re.MULTILINE)
    allowed = {'allow', 'ignore', 'deny', 'default'}
    if len(modes) == 1 and modes[0] in allowed:
        return modes[0], None
    if not modes and package_modes == ['allow']:
        return None, 'allow'
    if not modes and re.search(r'^No operations\.\s*$', dump, re.MULTILINE):
        defaults = re.findall(r'^Default mode:\s*([a-z]+)\s*$', dump, re.MULTILINE)
        if len(defaults) == 1 and defaults[0] in allowed:
            return None, defaults[0]
    raise ValueError('Expected one known notification app-op state')


def write_journal(path, state):
    """Keep original setting values without a device address until recovery succeeds."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as journal:
            json.dump(state, journal)
            journal.flush()
            os.fsync(journal.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def capture_state(phone):
    """Read the settings the run may change, without recording the device address."""
    granted, flags = phone.notification_state()
    state = {
        'exact': phone.exact_mode(),
        'notifications': [granted, sorted(flags)],
        'notification_appop': list(phone.notification_appop_state()),
        'battery': phone.battery_override(),
        'forced_idle': phone.forced_idle(),
        'screen': screen_on(phone.shell('dumpsys deviceidle').decode()),
    }
    if state['battery'] or state['forced_idle']:
        raise RuntimeError('Battery or Doze is already overridden; refusing to change it')
    return state


def recover_state(phone, state, backup):
    """Restore each original state; keep the backup if any restoration fails."""
    import time

    errors = []

    def attempt(name, action):
        try:
            action()
        except Exception as error:
            errors.append(f'{name}: {error}')

    def set_and_check(read, write, expected):
        if read() != expected:
            write(expected)
        if read() != expected:
            raise RuntimeError('Restored value did not take effect')

    attempt('Doze', lambda: set_and_check(phone.forced_idle, phone.set_forced_idle, state['forced_idle']))
    attempt('battery', lambda: set_and_check(phone.battery_override, phone.set_battery_override, state['battery']))
    notifications = (state['notifications'][0], frozenset(state['notifications'][1]))
    attempt('notification permission', lambda: set_and_check(phone.notification_state, phone.set_notification_state, notifications))
    attempt('notification app-op', lambda: phone.restore_notification_appop(tuple(state['notification_appop'])))
    attempt('exact-alarm app-op', lambda: set_and_check(phone.exact_mode, phone.set_exact_mode, state['exact']))

    def restore_preferences():
        phone.stop()
        phone.write_preferences(backup.read_bytes())
        phone.launch()

    if backup.exists():
        attempt('preferences', restore_preferences)

    def restore_display():
        expected = state['screen']
        if screen_on(phone.shell('dumpsys deviceidle').decode()) != expected:
            phone.shell(f'input keyevent {224 if expected else 223}')
        for _ in range(10):
            if screen_on(phone.shell('dumpsys deviceidle').decode()) == expected:
                return
            time.sleep(1)
        raise RuntimeError('Restored screen state did not take effect')

    attempt('screen', restore_display)
    if errors:
        raise RuntimeError('Recovery failed: ' + '; '.join(errors))
    backup.unlink(missing_ok=True)


def media_volume(dump):
    """Read the current music-stream volume from Android's media-session command."""
    matches = re.findall(r'\bvolume is (\d+) in range', dump)
    if len(matches) != 1:
        raise ValueError('Media volume is missing or ambiguous')
    return int(matches[0])


if __name__ == '__main__':
    main()
